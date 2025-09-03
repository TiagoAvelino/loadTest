package org.acme.mqttBroker;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.acme.kafka.KafkaSend;
import org.acme.tracing.TracingBridge;
import org.acme.tracing.messageparams.MqttSendMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.jboss.logging.Logger;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@RegisterForReflection
@ApplicationScoped
public class MqttConsumerService {

    private static final Logger LOGGER = Logger.getLogger(MqttConsumerService.class);

    // ---- Injected services ------------------------------------------------------
    @Inject
    Tracer tracer; // used only if tracingEnabled = true
    @Inject
    KafkaSend producer;
    @Inject
    ManagedExecutor managedExecutor;

    // ---- Runtime config (env-friendly) -----------------------------------------
    @ConfigProperty(name = "mqtt.url", defaultValue = "tcp://localhost:1883")
    String mqttUrl;

    @ConfigProperty(name = "mqtt.topic.filter", defaultValue = "mqtt-message-in/+/+/app/test/pull")
    String topicFilter;

    @ConfigProperty(name = "mqtt.subscribe.qos", defaultValue = "0")
    int subscribeQos;

    @ConfigProperty(name = "mqtt.cleanSession", defaultValue = "false")
    boolean cleanSession;

    @ConfigProperty(name = "mqtt.maxInflight", defaultValue = "1024")
    int maxInflight;

    // Replace outgoing Kafka topic suffix ".pull" -> ".push"
    @ConfigProperty(name = "mqtt.forward.replacePullWithPush", defaultValue = "true")
    boolean replacePullWithPush;

    // Consumer side performance knobs
    @ConfigProperty(name = "consumer.queue.capacity", defaultValue = "4096")
    int queueCapacity;

    @ConfigProperty(name = "consumer.worker.threads", defaultValue = "2")
    int workerThreads;

    // Optional tracing (disable for lowest latency)
    @ConfigProperty(name = "mqtt.tracing.enabled", defaultValue = "false")
    boolean tracingEnabled;

    // -----------------------------------------------------------------------------
    private MqttAsyncClient client;
    private volatile String localHostIp = "unknown";
    private ArrayBlockingQueue<Envelope> queue;

    // Simple envelope to avoid per-message allocations beyond the byte[]
    private static final class Envelope {
        final String topic;
        final byte[] payload;
        final long recvEpochMs;
        final long recvNano;

        Envelope(String topic, byte[] payload, long recvEpochMs, long recvNano) {
            this.topic = topic;
            this.payload = payload;
            this.recvEpochMs = recvEpochMs;
            this.recvNano = recvNano;
        }
    }

    @Startup(20)
    public void init() {
        try {
            localHostIp = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            LOGGER.warn("Failed to resolve host IP at startup", e);
        }

        queue = new ArrayBlockingQueue<>(Math.max(1024, queueCapacity));

        // Prefer async client to keep callback path thin and non-blocking
        try {
            client = new MqttAsyncClient(mqttUrl, "consumer-" + InetAddress.getLocalHost().getHostName(),
                    new MemoryPersistence());
        } catch (Exception e) {
            LOGGER.error("Failed to create MqttAsyncClient", e);
            return;
        }

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(cleanSession);
        options.setMaxInflight(maxInflight);
        options.setKeepAliveInterval(30);
        options.setConnectionTimeout(5);

        try {
            client.connect(options).waitForCompletion();
            LOGGER.infof("Connected MQTT consumer to %s (filter=%s, qos=%d)", mqttUrl, topicFilter, subscribeQos);
        } catch (Exception e) {
            LOGGER.error("Failed to connect to MQTT broker", e);
            return;
        }

        // Install a very thin callback: enqueue and return
        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                if (cause != null)
                    LOGGER.warn("MQTT connection lost: " + cause.getMessage(), cause);
                else
                    LOGGER.warn("MQTT connection lost");
            }

            @Override
            public void messageArrived(String topic, org.eclipse.paho.client.mqttv3.MqttMessage mqttMessage) {
                final byte[] payload = mqttMessage.getPayload();
                final long recvEpochMs = System.currentTimeMillis();

                final long recvNano = System.nanoTime();
                LOGGER.infof("Consumo MQTT: sentEpochMs=%d sentNano=%d", recvEpochMs, recvNano);

                Envelope env = new Envelope(topic, payload, recvEpochMs, recvNano);
                boolean offered = queue.offer(env); // non-blocking to protect callback thread
                if (!offered) {
                    // Drop oldest element to keep latency low (optional policy).
                    queue.poll();
                    queue.offer(env);
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debugf("Queue full, dropped oldest message. capacity=%d", queue.size());
                    }
                }
            }

            @Override
            public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
                // not used on consumer
            }
        });

        try {
            client.subscribe(topicFilter, subscribeQos).waitForCompletion();
            LOGGER.infof("Subscribed to '%s' with QoS=%d", topicFilter, subscribeQos);
        } catch (MqttException e) {
            LOGGER.error("Failed to subscribe to MQTT broker", e);
            return;
        }

        // Start workers
        int n = Math.max(1, workerThreads);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            managedExecutor.execute(() -> workerLoop(idx));
        }

        LOGGER.infof("Consumer started with %d worker(s), queue size=%d (remaining=%d, tracing=%s)",
                n,
                queue.size(),
                queue.remainingCapacity(),
                tracingEnabled ? "on" : "off");
    }

    private void workerLoop(int workerIndex) {
        final Duration idleLogEvery = Duration.ofSeconds(30);
        long lastLog = System.nanoTime();

        while (true) {
            try {
                Envelope env = queue.poll(500, TimeUnit.MILLISECONDS);
                if (env == null) {
                    long now = System.nanoTime();
                    if (LOGGER.isDebugEnabled() && now - lastLog > idleLogEvery.toNanos()) {
                        LOGGER.debugf("Worker-%d idle. Queue size=%d", workerIndex, queue.size());
                        lastLog = now;
                    }
                    continue;
                }
                handleEnvelope(env);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOGGER.warnf("Worker-%d interrupted, exiting", workerIndex);
                break;
            } catch (Throwable t) {
                LOGGER.errorf(t, "Worker-%d error", workerIndex);
            }
        }
    }

    private void handleEnvelope(Envelope env) {
        final String topic = env.topic;
        final byte[] payload = env.payload;
        final long recvEpochMs = env.recvEpochMs;
        final long recvNano = env.recvNano;

        if (payload == null || payload.length == 0) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debugf("Empty payload on topic=%s", topic);
            return;
        }

        // Optional tracing around "receive + process + forward"
        if (tracingEnabled) {
            var extractedParent = TracingBridge.extractFromMessage(payload);
            var receiveSpan = tracer.spanBuilder("mqtt.receive")
                    .setSpanKind(SpanKind.CONSUMER)
                    .setParent(extractedParent)
                    .setAttribute("messaging.system", "mqtt")
                    .setAttribute("messaging.destination", topic)
                    .setAttribute("message.payload_size_bytes", payload.length)
                    .startSpan();
            try (Scope rs = receiveSpan.makeCurrent()) {
                processAndForward(topic, payload, recvEpochMs, recvNano);
                receiveSpan.setStatus(StatusCode.OK);
            } catch (Exception e) {
                receiveSpan.recordException(e);
                receiveSpan.setStatus(StatusCode.ERROR, e.getMessage());
                throw e;
            } finally {
                receiveSpan.end();
            }
        } else {
            processAndForward(topic, payload, recvEpochMs, recvNano);
        }
    }

    private void processAndForward(String topic, byte[] payload, long recvEpochMs, long recvNano) {
        // Deserialize (Java serialization path kept)
        MqttSendMessage msg = deserialize(payload);
        if (msg == null) {
            LOGGER.error("Deserialization returned null");
            return;
        }

        // Compute end-to-end time using publisher-stamped epoch (if present)

        // Attach host for downstream
        if (msg.getHost() == null) {
            msg.setHost(localHostIp);
        }

        // Business logic (keep light)
        if (LOGGER.isDebugEnabled() && msg.getMessage() != null) {
            LOGGER.infof("Message received content: %d, %d " + msg.getMessage(), msg.getSentNano(),
                    msg.getSentEpochMs());
        }

        // Re-inject trace context (no-op if tracing is off)
        TracingBridge.injectIntoMessage(msg);

        // Transform topic/key
        String key = transformKey(topic);
        String dest = transformTopic(topic);
        if (replacePullWithPush && dest.endsWith(".pull")) {
            dest = dest.substring(0, dest.length() - 5) + ".push";
        }

        // Forward to Kafka (assumed async in your KafkaSend)
        producer.sendMessage(msg, key, dest);
    }

    private MqttSendMessage deserialize(byte[] data) {
        try (var ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            Object o = ois.readObject();
            if (o instanceof MqttSendMessage) {
                return (MqttSendMessage) o;
            }
            LOGGER.errorf("Unexpected object type: %s", (o == null ? "null" : o.getClass().getName()));
            return null;
        } catch (IOException | ClassNotFoundException e) {
            if (tracingEnabled) {
                Span.current().recordException(e);
                Span.current().setStatus(StatusCode.ERROR, "Deserialization failure");
            }
            LOGGER.error("Failed to deserialize payload", e);
            return null;
        }
    }

    @PreDestroy
    public void cleanup() {
        if (client != null && client.isConnected()) {
            try {
                client.disconnect();
                client.close();
                LOGGER.info("Disconnected from MQTT broker");
            } catch (MqttException e) {
                LOGGER.error("Failed to disconnect from MQTT broker", e);
            }
        }
    }

    // ---- Split-less topic transforms (low GC) -----------------------------------
    public static String transformKey(String mqttTopic) {
        Objects.requireNonNull(mqttTopic, "mqttTopic");
        int first = mqttTopic.indexOf('/');
        if (first < 0)
            throw new IllegalArgumentException("Invalid MQTT topic: " + mqttTopic);
        int second = mqttTopic.indexOf('/', first + 1);
        if (second < 0)
            throw new IllegalArgumentException("Invalid MQTT topic: " + mqttTopic);
        int third = mqttTopic.indexOf('/', second + 1);
        if (third < 0)
            throw new IllegalArgumentException("Invalid MQTT topic: " + mqttTopic);
        return mqttTopic.substring(0, third).replace('/', '.'); // e.g., mqtt-message-in.1.2
    }

    public static String transformTopic(String mqttTopic) {
        Objects.requireNonNull(mqttTopic, "mqttTopic");
        int first = mqttTopic.indexOf('/');
        if (first < 0)
            throw new IllegalArgumentException("Invalid MQTT topic: " + mqttTopic);
        int second = mqttTopic.indexOf('/', first + 1);
        if (second < 0)
            throw new IllegalArgumentException("Invalid MQTT topic: " + mqttTopic);
        int third = mqttTopic.indexOf('/', second + 1);
        if (third < 0)
            throw new IllegalArgumentException("Invalid MQTT topic: " + mqttTopic);
        return mqttTopic.substring(third + 1).replace('/', '.'); // e.g., app.test.pull
    }
}

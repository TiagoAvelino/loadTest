package org.acme.mqtt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.SocketFactory;

import org.acme.tracing.TracingBridge;
import org.acme.tracing.messageparams.MqttSendMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.jboss.logging.Logger;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@Startup(20)
@RegisterForReflection
@ApplicationScoped
public class MqttConsumer {

    private static final Logger LOGGER = Logger.getLogger(MqttConsumer.class);

    // --- Cluster-derived broker URL pieces (keep your original logic) ---
    @ConfigProperty(name = "POD_NAME")
    String podName;

    // Example: ".mqtt.svc.cluster.local:1883" or ".mqtt:1883"
    @ConfigProperty(name = "SERVICE")
    String service;

    // --- Minimal MQTT knobs ---
    @ConfigProperty(name = "mqtt.consumer.topic", defaultValue = "mqtt-message-in/1/2/app/test/push")
    String consumerTopic;

    @ConfigProperty(name = "mqtt.keepalive.seconds", defaultValue = "20")
    int keepAliveSeconds;

    @ConfigProperty(name = "mqtt.connect.timeout.seconds", defaultValue = "2")
    int connectTimeoutSeconds;

    @ConfigProperty(name = "mqtt.max.inflight", defaultValue = "5000")
    int maxInflight;

    // If your payloads are Java-serialized MqttSendMessage, keep true; set false
    // for JSON/text.
    @ConfigProperty(name = "mqtt.consumer.deserialize.enabled", defaultValue = "true")
    boolean deserializeEnabled;

    // --- TCP fast path (applied via custom SocketFactory) ---
    @ConfigProperty(name = "mqtt.socket.send.buf.bytes", defaultValue = "262144") // 256 KiB
    int soSndBuf;

    @ConfigProperty(name = "mqtt.socket.recv.buf.bytes", defaultValue = "262144") // 256 KiB
    int soRcvBuf;

    // --- Small worker pool to offload processing from the MQTT callback thread ---
    @ConfigProperty(name = "mqtt.consumer.parallelism", defaultValue = "4")
    int parallelism;

    // --- Tracing toggle ---
    @ConfigProperty(name = "mqtt.tracing.enabled", defaultValue = "true")
    boolean tracingEnabled;

    @Inject
    Tracer tracer;

    private IMqttAsyncClient client;
    private String brokerUrl; // computed from POD_NAME + SERVICE
    private ExecutorService workerPool;

    // For span peer attributes
    private String brokerHost;
    private Integer brokerPort;

    // ---- Boot: compute brokerUrl from pod/service and start ----
    public void onStart(@Observes StartupEvent ev) {
        brokerUrl = resolveBrokerUrlFromPodName(podName, service);
        brokerHost = brokerUrlHost(brokerUrl);
        long p = brokerUrlPort(brokerUrl);
        brokerPort = (p > 0 && p <= Integer.MAX_VALUE) ? (int) p : null;

        LOGGER.infof("Resolved MQTT broker URL: %s", brokerUrl);
        init();
    }

    private void init() {
        try {
            client = new MqttAsyncClient(brokerUrl, MqttAsyncClient.generateClientId(), new MemoryPersistence());

            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setCleanSession(true);
            opts.setAutomaticReconnect(true);
            opts.setKeepAliveInterval(Math.max(10, keepAliveSeconds));
            opts.setConnectionTimeout(Math.max(1, connectTimeoutSeconds));
            opts.setMaxInflight(Math.max(20, maxInflight));
            // TCP_NODELAY + larger socket buffers + explicit connect timeout
            opts.setSocketFactory(new TcpNoDelaySocketFactory(
                    opts.getConnectionTimeout(), Math.max(0, soSndBuf), Math.max(0, soRcvBuf)));

            IMqttToken tok = client.connect(opts);
            tok.waitForCompletion(Math.max(1000, connectTimeoutSeconds * 1000 + 500));
            LOGGER.infof("MQTT consumer connected %s (inflight=%d snd=%d rcv=%d)",
                    brokerUrl, opts.getMaxInflight(), soSndBuf, soRcvBuf);

            // tiny worker pool; no custom queue, just offload so the callback thread stays
            // free
            parallelism = Math.max(1, parallelism);
            workerPool = Executors.newFixedThreadPool(parallelism, r -> {
                var t = new Thread(r, "mqtt-consumer-worker");
                t.setDaemon(true);
                return t;
            });

            final String topic = consumerTopic;
            client.subscribe(topic, 0, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken a) {
                    LOGGER.infof("Subscribed to %s (QoS0)", topic);
                }

                @Override
                public void onFailure(IMqttToken a, Throwable e) {
                    LOGGER.error("Subscribe failed: " + topic, e);
                }
            }, (t, msg) -> {
                // keep callback tiny: copy payload, offload work
                final byte[] payload = msg.getPayload();
                final byte[] copy = (payload != null) ? Arrays.copyOf(payload, payload.length) : new byte[0];

                // Offload to worker pool so the MQTT I/O thread never blocks on user code
                java.util.concurrent.CompletableFuture.runAsync(() -> handleMessageWithTracing(t, copy), workerPool);
            });

        } catch (MqttException e) {
            LOGGER.error("MQTT connect/subscribe failed", e);
        }
    }

    /**
     * Offloaded path: extract parent context, start mqtt-consumer span, do work,
     * end span.
     */
    private void handleMessageWithTracing(String topic, byte[] payloadCopy) {
        if (!tracingEnabled) {
            // Fast path without tracing
            if (deserializeEnabled) {
                MqttSendMessage obj = deserialize(payloadCopy);
                if (obj != null)
                    processMessage(obj);
                else
                    LOGGER.warn("Deserialization returned null");
            } else {
                LOGGER.infof("Message on %s: %s", topic, new String(payloadCopy));
            }
            return;
        }

        // Extract parent context from message (produced by producer's
        // TracingBridge.injectIntoMessage)
        Context extracted;
        try {
            extracted = TracingBridge.extractFromMessage(payloadCopy);
            if (extracted == null)
                extracted = Context.root();
        } catch (Throwable ignored) {
            extracted = Context.root();
        }
        boolean hasParent = io.opentelemetry.api.trace.Span.fromContext(extracted).getSpanContext().isValid();

        // Build the consumer span (keeps name/attrs consistent with your common trace)
        var builder = TracingBridge.mqttConsumerSpan(
                tracer,
                hasParent ? extracted : Context.root(),
                topic,
                brokerHost,
                brokerPort,
                payloadCopy == null ? null : payloadCopy.length);
        if (!hasParent)
            builder.setNoParent();

        var span = builder.startSpan();
        try (Scope scope = span.makeCurrent()) {
            long t0 = System.nanoTime();
            MqttSendMessage obj = null;
            if (deserializeEnabled) {
                obj = deserialize(payloadCopy);
            }
            long deserMs = (System.nanoTime() - t0) / 1_000_000;

            t0 = System.nanoTime();
            if (deserializeEnabled) {
                if (obj != null)
                    processMessage(obj);
                else
                    LOGGER.warn("Deserialization returned null");
            } else {
                LOGGER.infof("Message on %s: %s", topic, new String(payloadCopy));
            }
            long userMs = (System.nanoTime() - t0) / 1_000_000;

            span.setAllAttributes(Attributes.of(
                    AttributeKey.stringKey("mqtt.consumer.thread"), Thread.currentThread().getName(),
                    AttributeKey.longKey("mqtt.consumer.deserialize_ms"), deserMs,
                    AttributeKey.longKey("mqtt.consumer.user_process_ms"), userMs));
            span.setStatus(StatusCode.OK);
        } catch (Throwable e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    // ---- Your message handling (keep fast) ----
    private void processMessage(MqttSendMessage message) {
        // Minimal example; keep work here short to maintain low latency.
        LOGGER.info("Message received: " + message.getMessage());
    }

    // ---- (Optional) Java serialization support ----
    private MqttSendMessage deserialize(byte[] data) {
        try (var bais = new ByteArrayInputStream(data);
                var ois = new ObjectInputStream(bais)) {
            return (MqttSendMessage) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            if (tracingEnabled) {
                Span.current().recordException(e);
                Span.current().setStatus(StatusCode.ERROR, "Deserialization failure");
            }
            LOGGER.error("Failed to deserialize message payload", e);
            return null;
        }
    }

    // ---- Shutdown ----
    @PreDestroy
    public void cleanup() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect().waitForCompletion(1000);
            }
        } catch (MqttException e) {
            LOGGER.error("Failed to disconnect MQTT client", e);
        } finally {
            try {
                if (client != null)
                    client.close();
            } catch (MqttException e) {
                LOGGER.error("Failed to close MQTT client", e);
            }
        }
        if (workerPool != null)
            workerPool.shutdownNow();
    }

    // --- Helpers: keep your original POD_NAME/SERVICE logic ---
    private static String resolveBrokerUrlFromPodName(String podName, String service) {
        int index = extractOrdinal(podName);
        // SERVICE should already contain host suffix + port, e.g. ".mqtt:1883" or
        // ".mqtt.svc.cluster.local:1883"
        return "tcp://mqtt-server-" + index + service;
    }

    private static int extractOrdinal(String name) {
        try {
            return Integer.parseInt(name.replaceAll(".*-(\\d+)$", "$1"));
        } catch (Exception e) {
            LOGGER.warnf("Cannot extract pod index from %s, defaulting to 0", name);
            return 0;
        }
    }

    private static String brokerUrlHost(String brokerUrl) {
        try {
            String u = brokerUrl.replace("tcp://", "");
            int i = u.indexOf(':');
            return i > 0 ? u.substring(0, i) : u;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static long brokerUrlPort(String brokerUrl) {
        try {
            String u = brokerUrl.replace("tcp://", "");
            int i = u.indexOf(':');
            return i > 0 ? Long.parseLong(u.substring(i + 1)) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Socket factory: TCP_NODELAY + send/recv buffers + connect timeouts. */
    static final class TcpNoDelaySocketFactory extends SocketFactory {
        private final int timeoutMs, sndBuf, rcvBuf;

        TcpNoDelaySocketFactory(int timeoutSeconds, int sndBuf, int rcvBuf) {
            this.timeoutMs = Math.max(1000, timeoutSeconds * 1000);
            this.sndBuf = sndBuf;
            this.rcvBuf = rcvBuf;
        }

        private Socket newSocket() {
            try {
                Socket s = new Socket();
                s.setTcpNoDelay(true);
                if (sndBuf > 0)
                    s.setSendBufferSize(sndBuf);
                if (rcvBuf > 0)
                    s.setReceiveBufferSize(rcvBuf);
                return s;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Socket createSocket() {
            return newSocket();
        }

        @Override
        public Socket createSocket(String host, int port) throws java.io.IOException {
            Socket s = newSocket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return s;
        }

        @Override
        public Socket createSocket(String host, int port, java.net.InetAddress lh, int lp) throws java.io.IOException {
            Socket s = newSocket();
            s.bind(new java.net.InetSocketAddress(lh, lp));
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return s;
        }

        @Override
        public Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            Socket s = newSocket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return s;
        }

        @Override
        public Socket createSocket(java.net.InetAddress addr, int port, java.net.InetAddress la, int lp)
                throws java.io.IOException {
            Socket s = newSocket();
            s.bind(new java.net.InetSocketAddress(la, lp));
            s.connect(new InetSocketAddress(addr, port), timeoutMs);
            return s;
        }
    }
}

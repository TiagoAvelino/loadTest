package org.acme.mqtt;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.SocketFactory;

import org.acme.tracing.messageparams.MqttSendMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * High-performance MQTT producer:
 * - Persistent MqttAsyncClient (auto-reconnect)
 * - QoS 0 fire-and-continue (non-blocking)
 * - TCP_NODELAY via custom SocketFactory
 * - Small executor + bounded queue to isolate callers from transient
 * back-pressure
 * - Lazy/idempotent initialization to avoid NPE when called early
 */
@ApplicationScoped
public class MqttProducer {

    private static final Logger LOGGER = Logger.getLogger(MqttProducer.class);

    private static final String MQTT_BROKER_PREFIX = "tcp://";
    private static final int MQTT_DEFAULT_PORT = 1883;
    private static final int QOS = 0; // fastest

    // ===== Config =====
    @Inject
    Tracer tracer;

    @ConfigProperty(name = "mqtt.tracing.enabled", defaultValue = "true")
    boolean tracingEnabled;

    @ConfigProperty(name = "mqtt.keepalive.seconds", defaultValue = "20")
    int keepAliveSeconds;

    @ConfigProperty(name = "mqtt.connect.timeout.seconds", defaultValue = "2")
    int connectTimeoutSeconds;

    @ConfigProperty(name = "mqtt.producer.queue.capacity", defaultValue = "2048")
    int queueCapacity;

    @ConfigProperty(name = "mqtt.producer.parallelism", defaultValue = "2")
    int parallelism;

    // topic is set by caller
    private volatile String topic = "";

    public String getTopic() {
        return this.topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    // ===== Runtime =====
    private volatile IMqttAsyncClient client;
    private volatile String currentBrokerHost;
    private volatile String currentBrokerUrl;

    private volatile ExecutorService pubPool;
    private volatile BlockingQueue<byte[]> outbound;
    private volatile boolean running;
    private volatile boolean initialized;

    @PostConstruct
    void init() {
        // best effort early init; produce() will ensure again
        safeInitOnce();
    }

    @PreDestroy
    void shutdown() {
        running = false;
        try {
            if (client != null && client.isConnected())
                client.disconnect();
        } catch (Exception ignored) {
        }
        try {
            if (client != null)
                client.close();
        } catch (Exception ignored) {
        }
        if (pubPool != null)
            pubPool.shutdownNow();
    }

    /** Public API: non-blocking enqueue for publish. */
    public void produce(MqttSendMessage mqttMes) {
        // Ensure everything is ready (idempotent, thread-safe)
        safeInitOnce();

        final String brokerHost = mqttMes.getHost();
        final String destTopic = this.topic;

        Objects.requireNonNull(brokerHost, "broker host must not be null");
        if (destTopic == null || destTopic.isBlank()) {
            throw new IllegalStateException("MQTT topic is not set; call setTopic(...) before produce()");
        }

        final byte[] payloadBytes = mqttMes.serialize();
        final String brokerUrl = MQTT_BROKER_PREFIX + brokerHost + ":" + MQTT_DEFAULT_PORT;

        ensureConnected(brokerHost, brokerUrl); // fast path if already connected

        // Offer to queue (bounded). For QoS 0, drop on back-pressure to protect
        // latency.
        if (!outbound.offer(payloadBytes)) {
            LOGGER.warn("MQTT producer outbound queue is full; dropping message to protect latency");
        }

        if (tracingEnabled) {
            var span = tracer.spanBuilder("mqtt.client.publish.enqueue")
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute("messaging.system", "mqtt")
                    .setAttribute("messaging.operation", "publish")
                    .setAttribute("messaging.destination_kind", "topic")
                    .setAttribute("messaging.destination", destTopic)
                    .setAttribute("messaging.protocol", "mqtt")
                    .setAttribute("messaging.protocol_version", "3.1.1")
                    .setAttribute("mqtt.qos", QOS)
                    .setAttribute("message.payload_size_bytes", payloadBytes.length)
                    .setAttribute("net.peer.name", brokerHost)
                    .setAttribute("net.peer.port", MQTT_DEFAULT_PORT)
                    .startSpan();
            span.setStatus(StatusCode.OK);
            span.end();
        }
    }

    // ==== Internal: init, connection, and drain loop ====

    private void safeInitOnce() {
        if (initialized)
            return;
        synchronized (this) {
            if (initialized)
                return;

            int n = Math.max(1, parallelism);
            pubPool = Executors.newFixedThreadPool(n, r -> {
                Thread t = new Thread(r, "mqtt-producer-pool");
                t.setDaemon(true);
                return t;
            });
            outbound = new ArrayBlockingQueue<>(Math.max(64, queueCapacity));
            running = true;

            for (int i = 0; i < n; i++) {
                pubPool.submit(this::drainLoop);
            }
            initialized = true;
            LOGGER.infof("MQTT producer initialized: workers=%d, queue=%d", n,
                    outbound.remainingCapacity() + outbound.size());
        }
    }

    private synchronized void ensureConnected(String brokerHost, String brokerUrl) {
        try {
            if (client != null
                    && brokerHost.equals(currentBrokerHost)
                    && (client.isConnected())) {
                return;
            }

            if (client == null || !brokerHost.equals(currentBrokerHost)) {
                if (client != null) {
                    try {
                        client.close();
                    } catch (Exception ignored) {
                    }
                }
                client = new MqttAsyncClient(brokerUrl, MqttAsyncClient.generateClientId());
                currentBrokerHost = brokerHost;
                currentBrokerUrl = brokerUrl;
            }

            if (!client.isConnected()) {
                MqttConnectOptions opts = new MqttConnectOptions();
                opts.setCleanSession(true);
                opts.setAutomaticReconnect(true);
                opts.setMaxReconnectDelay(5_000);
                opts.setKeepAliveInterval(keepAliveSeconds);
                opts.setConnectionTimeout(connectTimeoutSeconds);
                opts.setSocketFactory(new TcpNoDelaySocketFactory());

                DisconnectedBufferOptions dbo = new DisconnectedBufferOptions();
                dbo.setBufferEnabled(false); // QoS 0: do not buffer when offline
                client.setBufferOpts(dbo);

                IMqttToken tok = client.connect(opts);
                // Small wait to initiate connection; do not block the caller path
                tok.waitForCompletion(1_000);
                if (client.isConnected()) {
                    LOGGER.infof("Connected MQTT producer to %s", currentBrokerUrl);
                }
            }
        } catch (MqttException e) {
            LOGGER.warnf("MQTT connect attempt failed: %s (reason %s)", e.getMessage(), e.getReasonCode());
        }
    }

    private void drainLoop() {
        while (running) {
            try {
                byte[] payload = outbound.take();
                IMqttAsyncClient c = this.client;

                if (c == null || !c.isConnected()) {
                    // Not connected now → drop (QoS 0) to keep latency predictable
                    continue;
                }

                final String destTopic = this.topic;

                if (tracingEnabled) {
                    Span span = tracer.spanBuilder("mqtt.client.publish")
                            .setSpanKind(SpanKind.INTERNAL)
                            .setAttribute("messaging.system", "mqtt")
                            .setAttribute("messaging.operation", "publish")
                            .setAttribute("messaging.destination_kind", "topic")
                            .setAttribute("messaging.destination", destTopic)
                            .setAttribute("mqtt.qos", QOS)
                            .setAttribute("message.payload_size_bytes", payload.length)
                            .setAttribute("net.peer.name", currentBrokerHost)
                            .setAttribute("net.peer.port", MQTT_DEFAULT_PORT)
                            .startSpan();
                    long t0 = System.nanoTime();
                    try (Scope ignored = span.makeCurrent()) {
                        c.publish(destTopic, payload, QOS, false, null, new IMqttActionListener() {
                            @Override
                            public void onSuccess(IMqttToken token) {
                                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                                span.setAttribute("messaging.publish_socket_ms", ms);
                                span.setStatus(StatusCode.OK);
                                span.end();
                            }

                            @Override
                            public void onFailure(IMqttToken token, Throwable ex) {
                                span.recordException(ex);
                                span.setStatus(StatusCode.ERROR, "publish failure");
                                span.end();
                            }
                        });
                    }
                } else {
                    c.publish(destTopic, payload, QOS, false, null, null);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                LOGGER.warn("Error in MQTT publish loop", t);
            }
        }
    }

    /**
     * Socket factory that forces TCP_NODELAY (reduces Nagle delays on small
     * messages).
     */
    static final class TcpNoDelaySocketFactory extends SocketFactory {
        @Override
        public Socket createSocket() {
            try {
                Socket s = new Socket();
                s.setTcpNoDelay(true);
                return s;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Socket createSocket(String host, int port) throws java.io.IOException {
            Socket s = createSocket();
            s.connect(new InetSocketAddress(host, port));
            return s;
        }

        @Override
        public Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort)
                throws java.io.IOException {
            Socket s = createSocket();
            s.bind(new java.net.InetSocketAddress(localHost, localPort));
            s.connect(new InetSocketAddress(host, port));
            return s;
        }

        @Override
        public Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            Socket s = createSocket();
            s.connect(new InetSocketAddress(host, port));
            return s;
        }

        @Override
        public Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress,
                int localPort) throws java.io.IOException {
            Socket s = createSocket();
            s.bind(new java.net.InetSocketAddress(localAddress, localPort));
            s.connect(new InetSocketAddress(address, port));
            return s;
        }
    }
}

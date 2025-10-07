package org.acme.mqtt;

import org.acme.tracing.TracingBridge;
import org.acme.tracing.messageparams.MqttSendMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.net.SocketFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class MqttClientService {

    private static final Logger logger = Logger.getLogger(MqttClientService.class);

    // ===== Config =====
    @ConfigProperty(name = "POD_NAME")
    String podName;
    @ConfigProperty(name = "SERVICE")
    String service;

    // Socket/connection perf knobs (safe defaults)
    @ConfigProperty(name = "mqtt.keepalive.seconds", defaultValue = "20")
    int keepAliveSeconds;
    @ConfigProperty(name = "mqtt.connect.timeout.seconds", defaultValue = "2")
    int connectTimeoutSeconds;
    @ConfigProperty(name = "mqtt.max.inflight", defaultValue = "5000")
    int maxInflight;
    @ConfigProperty(name = "mqtt.socket.send.buf.bytes", defaultValue = "262144")
    int soSndBuf;
    @ConfigProperty(name = "mqtt.socket.recv.buf.bytes", defaultValue = "262144")
    int soRcvBuf;

    // Tracing toggle (helpful to isolate exporter overhead)
    @ConfigProperty(name = "mqtt.tracing.enabled", defaultValue = "true")
    boolean tracingEnabled;

    private String broker; // tcp://mqtt-server-<index><service>

    private IMqttAsyncClient client;
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    // OpenTelemetry
    @Inject
    Tracer tracer;

    // JSON: prebuilt writer for speed
    @Inject
    ObjectMapper objectMapper;
    private ObjectWriter mqttMessageWriter;

    @PostConstruct
    void configureBroker() {
        int index = extractOrdinal(podName);
        logger.infof("Resolved index: %d", index);
        broker = "tcp://mqtt-server-" + index + service;
        logger.infof("Resolved broker address: %s", broker);
        mqttMessageWriter = objectMapper.writerFor(MqttSendMessage.class);
    }

    public void init(String topic) {
        connectAndSubscribe(topic);
    }

    private void connectAndSubscribe(String topic) {
        if (client != null && client.isConnected())
            return;
        if (!connecting.compareAndSet(false, true))
            return;

        try {
            logger.infof("Connecting (async) to MQTT broker %s ...", broker);
            client = new MqttAsyncClient(broker, MqttAsyncClient.generateClientId(), new MemoryPersistence());

            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setCleanSession(true);
            opts.setAutomaticReconnect(true);
            opts.setKeepAliveInterval(Math.max(10, keepAliveSeconds));
            opts.setConnectionTimeout(Math.max(1, connectTimeoutSeconds));
            opts.setMaxInflight(Math.max(1000, maxInflight));
            opts.setSocketFactory(new TcpNoDelaySocketFactory(opts.getConnectionTimeout(), Math.max(0, soSndBuf),
                    Math.max(0, soRcvBuf)));

            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    logger.warn("MQTT connection lost: " + (cause == null ? "unknown" : cause.getMessage()));
                }

                @Override
                public void messageArrived(String t, MqttMessage m) {
                    // This producer subscribes only if you call init(topic) for self-consumption.
                    if (logger.isDebugEnabled())
                        logger.debugf("Message arrived. Topic: %s Size: %d", t,
                                m.getPayload() == null ? 0 : m.getPayload().length);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // QoS0 → deliveryComplete may not fire consistently, that's fine.
                }
            });

            client.connect(opts, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    connecting.set(false);
                    logger.info("MQTT connected.");
                    // Subscribe only if you want this service to consume too.
                    if (topic != null && !topic.isBlank()) {
                        try {
                            client.subscribe(topic, 0, null, new IMqttActionListener() {
                                @Override
                                public void onSuccess(IMqttToken a) {
                                    logger.infof("Subscribed to %s (QoS0)", topic);
                                }

                                @Override
                                public void onFailure(IMqttToken a, Throwable e) {
                                    logger.error("Subscribe failed: " + topic, e);
                                }
                            }, (t, msg) -> {
                            });
                        } catch (MqttException e) {
                            e.printStackTrace();
                        }
                    }
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    connecting.set(false);
                    logger.error("MQTT connect failed", exception);
                }
            });

        } catch (MqttException e) {
            connecting.set(false);
            logger.error("MQTT async connect failed", e);
        }
    }

    /**
     * Non-blocking publish: creates a PRODUCER parent span and injects context,
     * serializes to JSON once, and hands off to the broker without waiting.
     */
    public void publishMessage(String topic, MqttSendMessage payload) {
        ensureConnected(topic);

        // Parent span
        Span span = tracer.spanBuilder("mqtt.send")
                .setSpanKind(SpanKind.PRODUCER)
                .setAttribute("messaging.system", "mqtt")
                .setAttribute("messaging.operation", "publish")
                .setAttribute("messaging.destination_kind", "topic")
                .setAttribute("messaging.destination", topic == null ? "unknown" : topic)
                .startSpan();

        byte[] bytes = null;
        long t0 = System.nanoTime();

        try (Scope s = span.makeCurrent()) {
            if (tracingEnabled) {
                // Prefer your bridge (writes traceParent/traceState into payload)
                try {
                    TracingBridge.injectIntoMessage(payload);
                } catch (Throwable ignore) {
                    // fallback to global propagator (assumes setters exist)
                    try {
                        var carrier = new java.util.HashMap<String, String>(2);
                        GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                                .inject(io.opentelemetry.context.Context.current(), carrier, java.util.Map::put);
                        if (payload != null) {
                            payload.setTraceParent(carrier.get("traceparent"));
                            payload.setTraceState(carrier.get("tracestate"));
                        }
                    } catch (Throwable t) {
                        logger.debug("Fallback inject failed (non-fatal)", t);
                    }
                }
            }

            // Serialize once
            bytes = mqttMessageWriter.writeValueAsBytes(payload);

            MqttMessage message = new MqttMessage(bytes);
            message.setQos(0);

            // Non-blocking publish
            client.publish(topic, message, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    span.setStatus(StatusCode.OK);
                    span.end();
                    long t1 = System.nanoTime();
                    logger.infof("Publish enqueued in %.2f ms (qos0, async)", (t1 - t0) / 1_000_000.0);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    span.recordException(exception);
                    span.setStatus(StatusCode.ERROR, exception.getMessage());
                    span.end();
                    logger.error("Publish failed", exception);
                }
            });

        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, "serialize/publish exception");
            span.end();
            logger.error("Publish path failed", e);
        }
    }

    private void ensureConnected(String topic) {
        if (client == null || !client.isConnected()) {
            connectAndSubscribe(null); // we only need publish path; pass a topic here if you need local consumption
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (client != null) {
                if (client.isConnected())
                    client.disconnect().waitForCompletion(1000);
                client.close();
                logger.info("Disconnected MQTT async client");
            }
        } catch (MqttException e) {
            logger.error("Error while closing MQTT client", e);
        }
    }

    private int extractOrdinal(String n) {
        try {
            return Integer.parseInt(n.replaceAll(".*-(\\d+)$", "$1"));
        } catch (Exception e) {
            logger.warn("Cannot extract ordinal from pod name; default 0");
            return 0;
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
        public Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
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
        public Socket createSocket(java.net.InetAddress addr, int port, java.net.InetAddress la, int lp)
                throws java.io.IOException {
            Socket s = newSocket();
            s.bind(new java.net.InetSocketAddress(la, lp));
            s.connect(new InetSocketAddress(addr, port), timeoutMs);
            return s;
        }
    }
}

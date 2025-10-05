package org.acme.mqtt;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;

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

    @ConfigProperty(name = "POD_NAME")
    String podName;
    @ConfigProperty(name = "SERVICE")
    String service;

    @ConfigProperty(name = "mqtt.consumer.topic", defaultValue = "mqtt-message-in/1/2/app/test/push")
    String consumerTopic;

    @ConfigProperty(name = "mqtt.keepalive.seconds", defaultValue = "20")
    int keepAliveSeconds;
    @ConfigProperty(name = "mqtt.connect.timeout.seconds", defaultValue = "2")
    int connectTimeoutSeconds;
    @ConfigProperty(name = "mqtt.max.inflight", defaultValue = "5000")
    int maxInflight;

    @ConfigProperty(name = "mqtt.consumer.deserialize.enabled", defaultValue = "true")
    boolean deserializeEnabled;

    @ConfigProperty(name = "mqtt.socket.send.buf.bytes", defaultValue = "262144")
    int soSndBuf;
    @ConfigProperty(name = "mqtt.socket.recv.buf.bytes", defaultValue = "262144")
    int soRcvBuf;

    @ConfigProperty(name = "mqtt.consumer.parallelism", defaultValue = "4")
    int parallelism;

    @ConfigProperty(name = "mqtt.tracing.enabled", defaultValue = "true")
    boolean tracingEnabled;

    @Inject
    Tracer tracer;
    @Inject
    ObjectMapper objectMapper;
    private ObjectReader mqttMessageReader;

    private IMqttAsyncClient client;
    private String brokerUrl;
    private ExecutorService workerPool;

    private String brokerHost;
    private Integer brokerPort;

    // Getter to extract from a simple map carrier
    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    public void onStart(@Observes StartupEvent ev) {
        brokerUrl = resolveBrokerUrlFromPodName(podName, service);
        brokerHost = brokerUrlHost(brokerUrl);
        long p = brokerUrlPort(brokerUrl);
        brokerPort = (p > 0 && p <= Integer.MAX_VALUE) ? (int) p : null;

        mqttMessageReader = objectMapper.readerFor(MqttSendMessage.class);

        LOGGER.infof("MQTT consumer broker URL: %s; subscribing to: %s", brokerUrl, consumerTopic);
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
            opts.setSocketFactory(new TcpNoDelaySocketFactory(
                    opts.getConnectionTimeout(), Math.max(0, soSndBuf), Math.max(0, soRcvBuf)));

            IMqttToken tok = client.connect(opts);
            tok.waitForCompletion(Math.max(1000, connectTimeoutSeconds * 1000 + 500));
            LOGGER.infof("MQTT consumer connected %s (inflight=%d snd=%d rcv=%d)",
                    brokerUrl, opts.getMaxInflight(), soSndBuf, soRcvBuf);

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
                final byte[] payload = msg.getPayload();
                final byte[] copy = (payload != null) ? Arrays.copyOf(payload, payload.length) : new byte[0];
                java.util.concurrent.CompletableFuture.runAsync(() -> handleMessageWithTracing(t, copy), workerPool);
            });

        } catch (MqttException e) {
            LOGGER.error("MQTT connect/subscribe failed", e);
        }
    }

    private void handleMessageWithTracing(String topic, byte[] payloadCopy) {
        if (!tracingEnabled) {
            fastNoTracing(topic, payloadCopy);
            return;
        }

        // 1) Try your bridge
        Context extracted = null;
        try {
            extracted = TracingBridge.extractFromMessage(payloadCopy);
        } catch (Throwable ignored) {
            /* fall through */ }

        // 2) If that failed, try JSON (accept both traceparent/traceParent)
        if (extracted == null || !Span.fromContext(extracted).getSpanContext().isValid()) {
            Map<String, String> carrier = tryExtractFromJson(payloadCopy);
            if (!carrier.isEmpty()) {
                extracted = GlobalOpenTelemetry.getPropagators()
                        .getTextMapPropagator()
                        .extract(Context.root(), carrier, MAP_GETTER);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debugf("Extracted W3C context from JSON: tp=%s ts=%s",
                            carrier.get("traceparent"), carrier.get("tracestate"));
                }
            }
        }
        if (extracted == null)
            extracted = Context.root();

        boolean hasParent = Span.fromContext(extracted).getSpanContext().isValid();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debugf("MQTT extracted parent valid? %s", hasParent);
        }

        // ---- SPAN A: mqtt.receive (keep OPEN while processing)
        var receiveSpan = tracer.spanBuilder("mqtt.receive")
                .setSpanKind(SpanKind.CONSUMER)
                .setParent(hasParent ? extracted : Context.root())
                .setAttribute("messaging.system", "mqtt")
                .setAttribute("messaging.operation", "receive")
                .setAttribute("messaging.destination_kind", "topic")
                .setAttribute("messaging.destination", topic)
                .setAttribute("net.peer.name", brokerHost == null ? "unknown" : brokerHost)
                .setAttribute("net.peer.port", brokerPort == null ? 0 : brokerPort)
                .setAttribute("messaging.message_payload_size_bytes", payloadCopy == null ? 0 : payloadCopy.length)
                .startSpan();

        try (Scope receiveScope = receiveSpan.makeCurrent()) {
            MqttSendMessage obj = null;
            if (deserializeEnabled) {
                obj = deserializeJson(payloadCopy);
            }

            if (deserializeEnabled) {
                if (obj != null) {
                    processMessage(topic, payloadCopy, obj);
                } else {
                    LOGGER.warn("Deserialization returned null");
                }
            } else {
                LOGGER.infof("MQTT message on %s len=%d (deserialize disabled)", topic,
                        payloadCopy == null ? 0 : payloadCopy.length);
            }

            receiveSpan.setStatus(StatusCode.OK);
        } catch (Throwable outer) {
            receiveSpan.recordException(outer);
            receiveSpan.setStatus(StatusCode.ERROR, outer.getMessage());
            throw outer;
        } finally {
            receiveSpan.end(); // end parent AFTER child so UI nests correctly
        }
    }

    private Map<String, String> tryExtractFromJson(byte[] bytes) {
        Map<String, String> carrier = new HashMap<>(2);
        if (bytes == null || bytes.length == 0)
            return carrier;
        try {
            JsonNode n = objectMapper.readTree(bytes);
            // accept both cases
            JsonNode tp = n.hasNonNull("traceparent") ? n.get("traceparent") : n.get("traceParent");
            JsonNode ts = n.hasNonNull("tracestate") ? n.get("tracestate") : n.get("traceState");
            if (tp != null && !tp.asText().isBlank())
                carrier.put("traceparent", tp.asText());
            if (ts != null && !ts.asText().isBlank())
                carrier.put("tracestate", ts.asText());
        } catch (Exception ignore) {
        }
        return carrier;
    }

    private void fastNoTracing(String topic, byte[] payloadCopy) {
        if (deserializeEnabled) {
            MqttSendMessage obj = deserializeJson(payloadCopy);
            if (obj != null)
                processMessage(topic, payloadCopy, obj);
            else
                LOGGER.warn("Deserialization returned null");
        } else {
            LOGGER.infof("MQTT message on %s len=%d (tracing off, no deserialize)",
                    topic, payloadCopy == null ? 0 : payloadCopy.length);
        }
    }

    private void processMessage(String topic, byte[] raw, MqttSendMessage message) {
        int len = raw == null ? 0 : raw.length;
        String preview = message.getMessage();
        if (preview != null && preview.length() > 120)
            preview = preview.substring(0, 120) + "...";
        LOGGER.infof("MQTT consumed: topic=%s len=%d message=%s", topic, len, String.valueOf(preview));
    }

    private MqttSendMessage deserializeJson(byte[] data) {
        try {
            if (data == null || data.length == 0)
                return null;
            return mqttMessageReader.readValue(data);
        } catch (Exception e) {
            if (tracingEnabled) {
                Span.current().recordException(e);
                Span.current().setStatus(StatusCode.ERROR, "JSON deserialization failure");
            }
            LOGGER.error("Failed to deserialize JSON payload", e);
            return null;
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (client != null && client.isConnected())
                client.disconnect().waitForCompletion(1000);
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

    private static String resolveBrokerUrlFromPodName(String podName, String service) {
        int index = extractOrdinal(podName);
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

package org.acme.mqtt;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.acme.tracing.TracingBridge;
import org.acme.tracing.messageparams.MqttSendMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MqttConsumer {

    private static final Logger LOGGER = Logger.getLogger(MqttConsumer.class);

    @ConfigProperty(name = "mqtt.consumer.deserialize.enabled", defaultValue = "true")
    boolean deserializeEnabled;

    @ConfigProperty(name = "mqtt.tracing.enabled", defaultValue = "true")
    boolean tracingEnabled;

    // Tag the receive span with the configured subscription (connector doesn’t
    // expose inbound metadata)
    @ConfigProperty(name = "mp.messaging.incoming.mqtt-in.topic", defaultValue = "unknown")
    String configuredSubscription;

    @Inject
    Tracer tracer;

    @Inject
    ObjectMapper objectMapper;

    private ObjectReader mqttMessageReader;

    // TextMapGetter for Map-based carrier
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

    // Lenient regex for last-resort extraction (case-insensitive keys)
    private static final Pattern TP_RE = Pattern.compile("\"traceparent\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TS_RE = Pattern.compile("\"tracestate\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);

    @PostConstruct
    void init() {
        mqttMessageReader = objectMapper.readerFor(MqttSendMessage.class);
    }

    @Incoming("mqtt-in")
    @Blocking("mqtt-consumer")
    public CompletionStage<Void> consume(Message<byte[]> msg) {
        final byte[] wire = msg.getPayload();

        // Raw preview: shows the SmallRye wrapper if present
        if (LOGGER.isDebugEnabled()) {
            final String raw = wire == null ? "null" : new String(wire, java.nio.charset.StandardCharsets.UTF_8);
            LOGGER.debugf("CONSUMER raw payload: %s%s",
                    raw == null ? "null" : raw.substring(0, Math.min(raw.length(), 512)),
                    (raw != null && raw.length() > 512) ? "..." : "");
        }

        // Unwrap connector envelope → inner JSON we serialized on the producer
        final byte[] inner = unwrapIfWrapped(wire);

        // ---- Extract parent context (Bridge → JSON → Regex)
        Context parent = Context.root();
        boolean hasParent = false;

        if (tracingEnabled && inner != null && inner.length > 0) {
            // A) Your custom bridge (first choice)
            try {
                parent = TracingBridge.extractFromMessage(inner);
                hasParent = Span.fromContext(parent).getSpanContext().isValid();
                LOGGER.debugf("CONSUMER bridge parent valid? %s", hasParent);
            } catch (Throwable ignored) {
                // best-effort
            }

            // B) Fallback: try to read traceparent/tracestate from the inner JSON
            if (!hasParent) {
                Map<String, String> carrier = tryExtractMapFromInnerJson(inner);
                if (carrier.isEmpty()) {
                    LOGGER.debug("CONSUMER fallback carrier is empty (no trace headers in JSON).");
                } else {
                    parent = GlobalOpenTelemetry.getPropagators()
                            .getTextMapPropagator()
                            .extract(Context.root(), carrier, MAP_GETTER);
                    hasParent = Span.fromContext(parent).getSpanContext().isValid();
                }
            }
        }

        if (LOGGER.isDebugEnabled()) {
            if (hasParent) {
                SpanContext sc = Span.fromContext(parent).getSpanContext();
                LOGGER.debugf("MQTT CONSUME join traceId=%s spanId=%s topic=%s",
                        sc.getTraceId(), sc.getSpanId(), configuredSubscription);
            } else {
                LOGGER.debug("MQTT CONSUME has NO valid remote context → new root");
            }
        }

        // ---- Build the receive span with the correct parent
        var receiveBuilder = tracer.spanBuilder("mqtt.receive")
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("messaging.system", "mqtt")
                .setAttribute("messaging.operation", "receive")
                .setAttribute("messaging.destination_kind", "topic")
                .setAttribute("messaging.destination", configuredSubscription)
                .setAttribute("messaging.message_payload_size_bytes", inner == null ? 0 : inner.length);

        if (hasParent)
            receiveBuilder.setParent(parent);
        else
            receiveBuilder.setNoParent();

        var receiveSpan = receiveBuilder.startSpan();

        CompletionStage<Void> completion;
        try (Scope ignored = receiveSpan.makeCurrent()) {
            MqttSendMessage obj = null;

            if (deserializeEnabled) {
                var deser = tracer.spanBuilder("mqtt.deserialize").setSpanKind(SpanKind.INTERNAL).startSpan();
                try (Scope s = deser.makeCurrent()) {
                    obj = deserializeJson(inner);
                    deser.setStatus(StatusCode.OK);
                } catch (Throwable e) {
                    deser.recordException(e);
                    deser.setStatus(StatusCode.ERROR, "JSON deserialization failure");
                    throw e;
                } finally {
                    deser.end();
                }
            }

            var proc = tracer.spanBuilder("mqtt.process").setSpanKind(SpanKind.CONSUMER).startSpan();
            try (Scope s = proc.makeCurrent()) {
                if (deserializeEnabled && obj != null) {
                    // TODO: your business logic
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debugf("CONSUMER POJO traceparent='%s' tracestate='%s' message='%s'",
                                obj.getTraceParent(), obj.getTraceState(), obj.getMessage());
                    }
                }
                proc.setStatus(StatusCode.OK);
            } catch (Throwable e) {
                proc.recordException(e);
                proc.setStatus(StatusCode.ERROR, e.getMessage());
                throw e;
            } finally {
                proc.end();
            }

            receiveSpan.setStatus(StatusCode.OK);
            completion = msg.ack();
        } catch (Throwable t) {
            receiveSpan.recordException(t);
            receiveSpan.setStatus(StatusCode.ERROR, t.getMessage());
            completion = msg.nack(t);
        } finally {
            receiveSpan.end();
        }

        return completion;
    }

    // ========================= Helpers =========================

    /**
     * Unwrap SmallRye MQTT envelope: {"payload":"<base64>", ...} → decode payload.
     */
    private byte[] unwrapIfWrapped(byte[] wire) {
        if (wire == null || wire.length == 0)
            return wire;
        try {
            JsonNode root = objectMapper.readTree(wire);
            if (root.has("payload") && root.get("payload").isTextual()) {
                String b64 = root.get("payload").asText();
                try {
                    byte[] inner = java.util.Base64.getDecoder().decode(b64);
                    if (LOGGER.isDebugEnabled()) {
                        String s = new String(inner, java.nio.charset.StandardCharsets.UTF_8);
                        LOGGER.debugf("CONSUMER unwrapped inner JSON: %s%s",
                                s.substring(0, Math.min(s.length(), 512)),
                                s.length() > 512 ? "..." : "");
                    }
                    return inner;
                } catch (IllegalArgumentException notB64) {
                    if (LOGGER.isDebugEnabled())
                        LOGGER.debug("CONSUMER 'payload' is not base64; using raw bytes");
                }
            }
        } catch (Exception ignore) {
            // not JSON; leave wire as-is
        }
        return wire;
    }

    /**
     * Try to extract trace headers from the inner JSON; lenient casing + regex
     * fallback.
     */
    private Map<String, String> tryExtractMapFromInnerJson(byte[] bytes) {
        Map<String, String> carrier = new HashMap<>(2);
        if (bytes == null || bytes.length == 0)
            return carrier;

        String innerStr = null;
        try {
            JsonNode n = objectMapper.readTree(bytes);
            String tp = null, ts = null;

            JsonNode tpNode = n.get("traceparent");
            if (tpNode == null)
                tpNode = n.get("traceParent");
            if (tpNode != null && !tpNode.isNull())
                tp = tpNode.asText();

            JsonNode tsNode = n.get("tracestate");
            if (tsNode == null)
                tsNode = n.get("traceState");
            if (tsNode != null && !tsNode.isNull())
                ts = tsNode.asText();

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debugf("CONSUMER JSON probe -> traceparent='%s' tracestate='%s'", tp, ts);
            }

            if (tp != null && !tp.isBlank())
                carrier.put("traceparent", tp);
            if (ts != null && !ts.isBlank())
                carrier.put("tracestate", ts);

            if (carrier.isEmpty()) {
                innerStr = (innerStr != null) ? innerStr : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                String tp2 = matchGroup(innerStr, TP_RE);
                String ts2 = matchGroup(innerStr, TS_RE);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debugf("CONSUMER regex probe -> traceparent='%s' tracestate='%s'", tp2, ts2);
                }
                if (tp2 != null && !tp2.isBlank())
                    carrier.put("traceparent", tp2);
                if (ts2 != null && !ts2.isBlank())
                    carrier.put("tracestate", ts2);
            }
        } catch (Exception e) {
            innerStr = (innerStr != null) ? innerStr : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            String tp2 = matchGroup(innerStr, TP_RE);
            String ts2 = matchGroup(innerStr, TS_RE);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debugf("CONSUMER regex probe (JSON fail) -> traceparent='%s' tracestate='%s'", tp2, ts2);
            }
            if (tp2 != null && !tp2.isBlank())
                carrier.put("traceparent", tp2);
            if (ts2 != null && !ts2.isBlank())
                carrier.put("tracestate", ts2);
        }
        return carrier;
    }

    private static String matchGroup(String s, Pattern p) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private MqttSendMessage deserializeJson(byte[] data) {
        try {
            if (data == null || data.length == 0)
                return null;
            if (mqttMessageReader == null)
                mqttMessageReader = objectMapper.readerFor(MqttSendMessage.class);
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
}

package org.acme.tracing;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.acme.tracing.messageparams.MqttSendMessage;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

/**
 * Shared tracing utilities for MQTT (v3) and Kafka.
 * - MQTT v3 has no headers: propagate via payload fields
 * (traceparent/tracestate).
 * - Kafka: propagate via headers ("traceparent", "tracestate").
 */
public final class TracingBridge {

    private TracingBridge() {
    }

    // ---- Common keys ----
    private static final String TRACEPARENT = "traceparent";
    private static final String TRACESTATE = "tracestate";

    // ---- OpenTelemetry global ----
    private static final OpenTelemetry OTEL = GlobalOpenTelemetry.get();

    // ========================================================================
    // ============== MQTT PAYLOAD PROPAGATION ================
    // ========================================================================

    /** Inject current context into the mutable message payload (two strings). */
    public static void injectIntoMessage(MqttSendMessage msg) {
        if (msg == null)
            return;
        Map<String, String> carrier = new HashMap<>(2);
        OTEL.getPropagators().getTextMapPropagator().inject(Context.current(), carrier, MAP_SETTER);
        msg.setTraceParent(carrier.get(TRACEPARENT));
        msg.setTraceState(carrier.get(TRACESTATE));
    }

    /** Extract context from serialized MQTT payload (best-effort). */
    public static Context extractFromMessage(byte[] payload) {
        try {
            MqttSendMessage m = deserialize(payload);
            if (m == null)
                return Context.root();

            Map<String, String> carrier = new HashMap<>(2);
            if (m.getTraceParent() != null)
                carrier.put(TRACEPARENT, m.getTraceParent());
            if (m.getTraceState() != null)
                carrier.put(TRACESTATE, m.getTraceState());
            return OTEL.getPropagators().getTextMapPropagator().extract(Context.root(), carrier, MAP_GETTER);
        } catch (Exception ignored) {
            return Context.root();
        }
    }

    // ---- MQTT Span helpers ----

    /** Build a standardized MQTT CONSUMER span (not started). */
    public static SpanBuilder mqttConsumerSpan(Tracer tracer, Context parent, String topic,
            String brokerHost, Integer brokerPort, Integer payloadBytes) {
        AttributesBuilder ab = Attributes.builder()
                .put("messaging.system", "mqtt")
                .put("messaging.operation", "receive")
                .put("messaging.destination_kind", "topic")
                .put("messaging.destination", nullSafe(topic))
                .put("messaging.protocol", "mqtt")
                .put("messaging.protocol_version", "3.1.1");
        if (brokerHost != null)
            ab.put("net.peer.name", brokerHost);
        if (brokerPort != null)
            ab.put("net.peer.port", brokerPort);
        if (payloadBytes != null)
            ab.put("message.payload_size_bytes", payloadBytes);

        return tracer.spanBuilder("mqtt.receive")
                .setSpanKind(SpanKind.CONSUMER)
                .setParent(parent)
                .setAllAttributes(ab.build());
    }

    /** Build a standardized MQTT PRODUCER span (not started). */
    public static SpanBuilder mqttProducerSpan(Tracer tracer, String topic,
            String brokerHost, Integer brokerPort, Integer payloadBytes) {
        AttributesBuilder ab = Attributes.builder()
                .put("messaging.system", "mqtt")
                .put("messaging.operation", "publish")
                .put("messaging.destination_kind", "topic")
                .put("messaging.destination", nullSafe(topic))
                .put("messaging.protocol", "mqtt")
                .put("messaging.protocol_version", "3.1.1");
        if (brokerHost != null)
            ab.put("net.peer.name", brokerHost);
        if (brokerPort != null)
            ab.put("net.peer.port", brokerPort);
        if (payloadBytes != null)
            ab.put("message.payload_size_bytes", payloadBytes);

        return tracer.spanBuilder("mqtt.publish")
                .setSpanKind(SpanKind.PRODUCER)
                .setAllAttributes(ab.build());
    }

    // ========================================================================
    // ============== KAFKA HEADER PROPAGATION ================
    // ========================================================================

    /** Inject current context into Kafka headers. */
    public static void injectIntoKafkaHeaders(Headers headers) {
        OTEL.getPropagators().getTextMapPropagator().inject(Context.current(), headers, KAFKA_HEADERS_SETTER);
    }

    /** Extract context from Kafka headers. */
    public static Context extractFromKafkaHeaders(Headers headers) {
        return OTEL.getPropagators().getTextMapPropagator().extract(Context.root(), headers, KAFKA_HEADERS_GETTER);
    }

    // ---- Kafka Span helpers ----

    /** Build a standardized Kafka CONSUMER span (not started). */
    public static SpanBuilder kafkaConsumerSpan(Tracer tracer, Context parent, String topic, String key) {
        AttributesBuilder ab = Attributes.builder()
                .put("messaging.system", "kafka")
                .put("messaging.operation", "receive")
                .put("messaging.destination_kind", "topic")
                .put("messaging.destination", nullSafe(topic))
                .put("messaging.kafka.message_key", nullSafe(key));
        return tracer.spanBuilder("kafka.receive")
                .setSpanKind(SpanKind.CONSUMER)
                .setParent(parent)
                .setAllAttributes(ab.build());
    }

    /** Build a standardized Kafka PRODUCER span (not started). */
    public static SpanBuilder kafkaProducerSpan(Tracer tracer, String topic, String key, String bootstrapServers) {
        AttributesBuilder ab = Attributes.builder()
                .put("messaging.system", "kafka")
                .put("messaging.operation", "send")
                .put("messaging.destination_kind", "topic")
                .put("messaging.destination", nullSafe(topic))
                .put("messaging.kafka.message_key", nullSafe(key));
        if (bootstrapServers != null) {
            ab.put("messaging.kafka.bootstrap_servers", bootstrapServers);
        }
        return tracer.spanBuilder("kafka.send")
                .setSpanKind(SpanKind.PRODUCER)
                .setAllAttributes(ab.build());
    }

    // ========================================================================
    // ============== INTERNALS ================
    // ========================================================================

    // Map setter/getter used for MQTT payload propagation
    private static final TextMapSetter<Map<String, String>> MAP_SETTER = (carrier, key, value) -> {
        if (carrier != null && key != null && value != null)
            carrier.put(key, value);
    };
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

    // Kafka headers setter/getter
    private static final TextMapSetter<Headers> KAFKA_HEADERS_SETTER = (headers, key, value) -> {
        if (headers == null || key == null || value == null)
            return;
        headers.remove(key); // avoid duplicates on retries
        headers.add(key, value.getBytes(StandardCharsets.UTF_8));
    };
    private static final TextMapGetter<Headers> KAFKA_HEADERS_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers carrier) {
            if (carrier == null)
                return java.util.List.of();
            Header[] arr = carrier.toArray();
            java.util.List<String> keys = new java.util.ArrayList<>(arr.length);
            for (Header h : arr)
                keys.add(h.key());
            return keys;
        }

        @Override
        public String get(Headers carrier, String key) {
            if (carrier == null || key == null)
                return null;
            Header h = carrier.lastHeader(key);
            return (h == null || h.value() == null) ? null : new String(h.value(), StandardCharsets.UTF_8);
        }
    };

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }

    // Minimal deserializer mirroring your services (avoid duplicating stream code
    // elsewhere)
    private static MqttSendMessage deserialize(byte[] data) {
        if (data == null)
            return null;
        try (var bais = new java.io.ByteArrayInputStream(data);
                var ois = new java.io.ObjectInputStream(bais)) {
            return (MqttSendMessage) ois.readObject();
        } catch (Exception e) {
            return null;
        }
    }

    // Convenience attribute key if you want to report latencies as a double
    public static final AttributeKey<Double> ATTR_LATENCY_MS = AttributeKey.doubleKey("messaging.publish_latency_ms");
}

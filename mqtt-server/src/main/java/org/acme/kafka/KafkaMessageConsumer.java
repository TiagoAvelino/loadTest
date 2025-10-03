package org.acme.kafka;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.acme.mqttBroker.MqttProducer;
import org.acme.tracing.TracingBridge;
import org.acme.tracing.messageparams.MqttSendMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;

@ApplicationScoped
public class KafkaMessageConsumer {

    private static final Logger LOGGER = Logger.getLogger(KafkaMessageConsumer.class.getName());

    @Inject
    Tracer tracer;
    @Inject
    MqttProducer mqttProducer;

    // Getter to read W3C context from Kafka headers
    private static final TextMapGetter<ConsumerRecord<String, MqttSendMessage>> KAFKA_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(ConsumerRecord<String, MqttSendMessage> carrier) {
            List<String> keys = new ArrayList<>();
            if (carrier == null || carrier.headers() == null)
                return keys;
            for (Header h : carrier.headers())
                keys.add(h.key());
            return keys;
        }

        @Override
        public String get(ConsumerRecord<String, MqttSendMessage> carrier, String key) {
            if (carrier == null || key == null)
                return null;
            Header h = carrier.headers().lastHeader(key);
            return (h == null) ? null : new String(h.value(), StandardCharsets.UTF_8);
        }
    };

    // Fallback getter for extracting from a simple map (when context was injected
    // into the value)
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

    @Incoming("kafka-channel")
    public MqttSendMessage consumeMessages(ConsumerRecord<String, MqttSendMessage> record) {
        final String key = record.key();
        final String topic = record.topic();

        // ---------- PARENT EXTRACTION ----------
        Context extracted = GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.root(), record, KAFKA_GETTER);

        // If headers didn’t carry context, fall back to value (traceparent/tracestate
        // in message)
        if (!Span.fromContext(extracted).getSpanContext().isValid()) {
            MqttSendMessage v = record.value();
            if (v != null && (v.getTraceParent() != null || v.getTraceState() != null)) {
                Map<String, String> carrier = new HashMap<>(2);
                if (v.getTraceParent() != null)
                    carrier.put("traceparent", v.getTraceParent());
                if (v.getTraceState() != null)
                    carrier.put("tracestate", v.getTraceState());
                OpenTelemetry otel = GlobalOpenTelemetry.get();
                extracted = otel.getPropagators().getTextMapPropagator()
                        .extract(Context.root(), carrier, MAP_GETTER);
            }
        }
        boolean hasParent = Span.fromContext(extracted).getSpanContext().isValid();

        // ---------- SPAN 1: kafka.receive (CONSUMER) ----------
        var receiveSpan = tracer.spanBuilder("kafka.receive")
                .setSpanKind(SpanKind.CONSUMER)
                .setParent(hasParent ? extracted : Context.root())
                .setAttribute("messaging.system", "kafka")
                .setAttribute("messaging.operation", "receive")
                .setAttribute("messaging.destination_kind", "topic")
                .setAttribute("messaging.destination", topic)
                .setAttribute("messaging.kafka.partition", record.partition())
                .setAttribute("messaging.kafka.offset", record.offset())
                .startSpan();

        try (Scope receiveScope = receiveSpan.makeCurrent()) {
            // Optional: add lag metric without holding the span open long
            try {
                long lagMs = (record.timestamp() > 0) ? (System.currentTimeMillis() - record.timestamp()) : -1L;
                if (lagMs >= 0)
                    receiveSpan.setAttribute("messaging.kafka.lag_ms", lagMs);
                receiveSpan.setStatus(StatusCode.OK);
            } catch (Throwable t) {
                receiveSpan.recordException(t);
                receiveSpan.setStatus(StatusCode.ERROR, "receive metadata error");
            } finally {
                receiveSpan.end(); // keep it very short
            }

            // ---------- SPAN 2: kafka.process (INTERNAL) ----------
            Span processSpan = tracer.spanBuilder("kafka.process")
                    .setSpanKind(SpanKind.INTERNAL)
                    // parent is the current context (receive)
                    .startSpan();

            try (Scope processScope = processSpan.makeCurrent()) {
                // ---- message handling
                MqttSendMessage message = record.value();
                if (message == null || message.getMessage() == null) {
                    message = new MqttSendMessage();
                    message.setMessage("Message Nula");
                }

                // Read the x-mqtt-topic header (where upstream told us to publish on MQTT)
                var header = record.headers().lastHeader("x-mqtt-topic");
                String mqttTopic = (header == null) ? null
                        : new String(header.value(), StandardCharsets.UTF_8);

                LOGGER.infof("Kafka consume: key=%s topic=%s x-mqtt-topic=%s message=%s",
                        key, topic, mqttTopic, message.getMessage());

                // Inject CURRENT context so downstream continues this same trace.
                TracingBridge.injectIntoMessage(message);

                // Forward to MQTT under this span; producer will create mqtt.send as a CHILD
                mqttProducer.setTopic(mqttTopic);
                mqttProducer.produce(message);

                processSpan.setStatus(StatusCode.OK);
                return message;

            } catch (Exception e) {
                processSpan.recordException(e);
                processSpan.setStatus(StatusCode.ERROR, e.getMessage());
                throw e;
            } finally {
                processSpan.end();
            }
        }
    }
}

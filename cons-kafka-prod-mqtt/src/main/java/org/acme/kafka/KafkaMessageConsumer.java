package org.acme.kafka;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.acme.mqtt.MqttProducer;
import org.acme.tracing.TracingBridge;
import org.acme.tracing.messageparams.MqttSendMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

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

    // Build MQTT broker URL from ordinal: String.format(brokerPattern, ordinal)
    @jakarta.inject.Inject
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "app.mqtt.brokerPattern")
    String brokerPattern;

    // ---- W3C Propagation helpers (unchanged) ----
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
        final String kafkaTopic = record.topic();

        // ---- Extract parent trace ----
        Context extracted = GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.root(), record, KAFKA_GETTER);

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

        // ---- Span: kafka.receive ----
        var receiveSpan = tracer.spanBuilder("kafka.receive")
                .setSpanKind(SpanKind.CONSUMER)
                .setParent(hasParent ? extracted : Context.root())
                .setAttribute("messaging.system", "kafka")
                .setAttribute("messaging.operation", "receive")
                .setAttribute("messaging.destination_kind", "topic")
                .setAttribute("messaging.destination", kafkaTopic)
                .setAttribute("messaging.kafka.partition", record.partition())
                .setAttribute("messaging.kafka.offset", record.offset())
                .startSpan();

        try (Scope scope = receiveSpan.makeCurrent()) {
            try {
                long lagMs = (record.timestamp() > 0) ? (System.currentTimeMillis() - record.timestamp()) : -1L;
                if (lagMs >= 0)
                    receiveSpan.setAttribute("messaging.kafka.lag_ms", lagMs);
                receiveSpan.setStatus(StatusCode.OK);
            } catch (Throwable t) {
                receiveSpan.recordException(t);
                receiveSpan.setStatus(StatusCode.ERROR, "receive metadata error");
            } finally {
                receiveSpan.end();
            }

            // ---- Span: kafka.process ----
            Span processSpan = tracer.spanBuilder("kafka.process")
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();

            try (Scope ps = processSpan.makeCurrent()) {
                MqttSendMessage message = record.value();
                if (message == null || message.getMessage() == null) {
                    message = new MqttSendMessage();
                    message.setMessage("Message Nula");
                }

                var header = record.headers().lastHeader("x-mqtt-topic");
                String mqttTopic = (header == null) ? null : new String(header.value(), StandardCharsets.UTF_8);

                // Map Kafka topic "mqtt-service-<N>" → MQTT broker "mqtt-server-<N>"
                int ord = extractOrdinal(kafkaTopic);
                if (ord < 0) {
                    String err = "Kafka topic must match 'mqtt-service-<N>' but was: " + kafkaTopic;
                    LOGGER.error(err);
                    processSpan.setStatus(StatusCode.ERROR, err);
                    return message;
                }
                String targetBrokerUrl = String.format(brokerPattern, ord);

                // Force this publish to that broker (override)
                message.setHost(targetBrokerUrl);

                LOGGER.infof("Consume key=%s topic=%s -> broker=%s, mqttTopic=%s, len=%d",
                        key, kafkaTopic, targetBrokerUrl, mqttTopic,
                        message.getMessage() == null ? 0 : message.getMessage().length());

                // Propagate tracing downstream into MQTT
                TracingBridge.injectIntoMessage(message);

                // Publish (MqttProducer reads message.host and honors it)
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

    private static int extractOrdinal(String topic) {
        if (topic == null)
            return -1;
        int dash = topic.lastIndexOf('-');
        if (dash < 0 || dash + 1 >= topic.length())
            return -1;
        try {
            return Integer.parseInt(topic.substring(dash + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

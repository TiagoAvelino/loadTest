package org.acme.kafka;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.acme.tracing.TracingBridge;
import org.acme.tracing.messageparams.MqttSendMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
import io.smallrye.reactive.messaging.kafka.KafkaClientService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class KafkaMessageConsumer {

    private static final Logger LOGGER = Logger.getLogger(KafkaMessageConsumer.class.getName());

    @ConfigProperty(name = "mqtt.topic.pattern")
    String mqttTopicPattern;

    @ConfigProperty(name = "kafka.tracing.enabled", defaultValue = "true")
    boolean tracingEnabled;

    @Inject
    KafkaClientService kafkaClientService;

    @Inject
    KafkaSend kafkaSend;

    @Inject
    Tracer tracer;

    private final ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor();

    // Fallback extractor from value fields (traceparent/tracestate) when no Kafka
    // headers exist
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

    @Incoming("app.test.push")
    public void consume(ConsumerRecord<String, MqttSendMessage> record) {
        final String key = record.key();
        final String topic = record.topic();
        final long nowMs = System.currentTimeMillis();
        final long nowNs = System.nanoTime();
        LOGGER.infof("Consumo kafka: sentEpochMs=%d sentNano=%d", nowMs, nowNs);

        // If tracing disabled, just process
        if (!tracingEnabled) {
            processRecord(record, Context.root());
            return;
        }

        // Extract upstream context (prefer headers; fallback to value)
        Context extracted = TracingBridge.extractFromKafkaHeaders(record.headers());
        if (!Span.fromContext(extracted).getSpanContext().isValid()) {
            MqttSendMessage v = record.value();
            if (v != null && (v.getTraceParent() != null || v.getTraceState() != null)) {
                Map<String, String> carrier = new HashMap<>(2);
                if (v.getTraceParent() != null)
                    carrier.put("traceparent", v.getTraceParent());
                if (v.getTraceState() != null)
                    carrier.put("tracestate", v.getTraceState());
                OpenTelemetry otel = GlobalOpenTelemetry.get();
                extracted = otel.getPropagators().getTextMapPropagator().extract(Context.root(), carrier, MAP_GETTER);
            }
        }
        boolean hasParent = Span.fromContext(extracted).getSpanContext().isValid();

        // (1) Very short "receive" span (CONSUMER)
        var recvBuilder = tracer.spanBuilder("kafka.receive")
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("messaging.system", "kafka")
                .setAttribute("messaging.destination", topic)
                .setAttribute("messaging.destination_kind", "topic")
                .setAttribute("messaging.kafka.partition", record.partition())
                .setAttribute("messaging.kafka.offset", record.offset())
                .setAttribute("messaging.kafka.consumer_group", "app.test.push");

        if (hasParent) {
            recvBuilder.setParent(extracted);
        } else {
            recvBuilder.setNoParent();
        }

        Span receiveSpan = recvBuilder.startSpan();

        // Make the receive span current only while we add quick attributes
        try (Scope ignored = receiveSpan.makeCurrent()) {
            long lagMs = (record.timestamp() > 0) ? (System.currentTimeMillis() - record.timestamp()) : -1L;
            if (lagMs >= 0)
                receiveSpan.setAttribute("messaging.kafka.lag_ms", (long) lagMs);
            receiveSpan.setAttribute("messaging.message_payload_size_bytes", estimatePayloadBytes(record.value()));
            receiveSpan.setStatus(StatusCode.OK);
        } catch (Exception e) {
            receiveSpan.recordException(e);
            receiveSpan.setStatus(StatusCode.ERROR, e.getMessage());
        } finally {
            receiveSpan.end();
        }

        // (2) "process" span as a child of the receive span / extracted context
        Span processSpan = tracer.spanBuilder("kafka.process")
                .setSpanKind(SpanKind.INTERNAL)
                .setParent(hasParent ? extracted : Context.current()) // extracted if valid, otherwise current
                .startSpan();

        try (Scope ps = processSpan.makeCurrent()) {
            processRecord(record, Context.current());
            processSpan.setStatus(StatusCode.OK);
        } catch (Exception e) {
            processSpan.recordException(e);
            processSpan.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            processSpan.end();
        }
    }

    private void processRecord(ConsumerRecord<String, MqttSendMessage> record, Context parentCtx) {
        final String key = record.key();

        try {
            pauseConsumer("app.test.push");

            MqttSendMessage message = record.value();
            if (message == null) {
                message = new MqttSendMessage();
                message.setMessage("Message Nula");
            } else {
                message.setMessage((message.getMessage() == null ? "" : message.getMessage()) + " Mensagem consumida");
            }

            System.out.printf("Message: %s e host: %s%n", message.getMessage(), message.getHost());

            // Compute the MQTT topic you want to propagate
            String mqttTopic = String.format(mqttTopicPattern, key, record.topic()).replace(".", "/");

            // Choose the Kafka topic you’re sending to (keeps your original intent of using
            // host)
            String kafkaTopic = message.getHost();

            if (tracingEnabled) {
                Span produceSpan = tracer.spanBuilder("kafka.produce")
                        .setSpanKind(SpanKind.PRODUCER)
                        .setParent(parentCtx)
                        .setAttribute("messaging.system", "kafka")
                        .setAttribute("messaging.destination", kafkaTopic == null ? "" : kafkaTopic)
                        .setAttribute("messaging.destination_kind", "topic")
                        .startSpan();

                try (Scope ps = produceSpan.makeCurrent()) {
                    TracingBridge.injectIntoMessage(message);
                    kafkaSend.sendMessage(message, key, kafkaTopic, mqttTopic);
                    produceSpan.setAttribute("messaging.mqtt.topic", mqttTopic);
                    produceSpan.setStatus(StatusCode.OK);
                } catch (Exception e) {
                    produceSpan.recordException(e);
                    produceSpan.setStatus(StatusCode.ERROR, e.getMessage());
                    throw e;
                } finally {
                    produceSpan.end();
                }
            } else {
                TracingBridge.injectIntoMessage(message);
                kafkaSend.sendMessage(message, key, kafkaTopic, mqttTopic);
            }

            System.out.println(message);
        } catch (Exception e) {
            System.err.println("Erro ao processar mensagem Kafka:");
            e.printStackTrace();
        } finally {
            resumeConsumer("app.test.push");
        }
    }

    private void pauseConsumer(String channel) {
        try {
            kafkaClientService.getConsumer(channel).pause();
            System.out.println("Consumo pausado para o canal: " + channel);
        } catch (Exception e) {
            System.err.println("Erro ao pausar o consumidor para o canal " + channel + ":");
            e.printStackTrace();
        }
    }

    private void resumeConsumer(String channel) {
        try {
            kafkaClientService.getConsumer(channel).resume();
        } catch (Exception e) {
            System.err.println("Erro ao retomar o consumidor para o canal " + channel + ":");
            e.printStackTrace();
        }
    }

    public void onPartitionsRevoked() {
        System.out.println("Partitions are being revoked, committing offsets and cleaning up...");
        cleanupExecutor.submit(() -> {
            try {
                System.out.println("Quick cleanup tasks for revoked partitions...");
            } catch (Exception e) {
                System.err.println("Error during partition cleanup:");
                e.printStackTrace();
            }
        });
    }

    public void gracefulShutdown() {
        try {
            System.out.println("Shutting down gracefully...");
            cleanupExecutor.shutdownNow();
        } catch (Exception e) {
            System.err.println("Error during graceful shutdown:");
            e.printStackTrace();
        }
    }

    /**
     * Lightweight and null-safe estimation of payload size,
     * avoiding any heavy serialization.
     */
    private static int estimatePayloadBytes(MqttSendMessage v) {
        if (v == null)
            return 0;
        int sum = 0;
        if (v.getMessage() != null)
            sum += v.getMessage().getBytes(StandardCharsets.UTF_8).length;
        if (v.getHost() != null)
            sum += v.getHost().getBytes(StandardCharsets.UTF_8).length;
        if (v.getJwt() != null)
            sum += v.getJwt().getBytes(StandardCharsets.UTF_8).length;
        if (v.getTraceParent() != null)
            sum += v.getTraceParent().getBytes(StandardCharsets.UTF_8).length;
        if (v.getTraceState() != null)
            sum += v.getTraceState().getBytes(StandardCharsets.UTF_8).length;
        // Add a small structural overhead estimate
        return sum + 32;
    }
}

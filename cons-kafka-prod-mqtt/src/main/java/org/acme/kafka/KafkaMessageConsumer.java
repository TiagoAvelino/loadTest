package org.acme.kafka;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.acme.mqtt.MqttProducer;
import org.acme.tracing.TracingBridge;
import org.acme.tracing.messageparams.MqttSendMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.smallrye.reactive.messaging.kafka.KafkaClientService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class KafkaMessageConsumer {

    @Inject
    Tracer tracer;

    @ConfigProperty(name = "kafka.topic")
    String kafkaTopic;

    @ConfigProperty(name = "mqtt.topic.pattern")
    String mqttTopicPattern;

    @Inject
    KafkaClientService kafkaClientService;

    private final ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor();

    @Incoming("app.test.push")
    public void consume(ConsumerRecord<String, MqttSendMessage> record) {
        final String key = record.key();
        final String topic = record.topic();
        final Headers headers = record.headers();

        // 1) Extract upstream context from Kafka headers -> continue SAME trace
        Context parent = TracingBridge.extractFromKafkaHeaders(headers);

        // 2) Standardized Kafka CONSUMER span
        Span receiveSpan = TracingBridge.kafkaConsumerSpan(tracer, parent, topic, key).startSpan();

        try (Scope rs = receiveSpan.makeCurrent()) {
            // (optional) pause/resume as part of processing window
            pauseConsumer("app.test.push");

            // 3) Message normalization/processing under a child INTERNAL span
            Span work = tracer.spanBuilder("business.process")
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
            MqttSendMessage message;
            try (Scope ws = work.makeCurrent()) {
                message = record.value();
                if (message == null) {
                    message = new MqttSendMessage();
                    message.setMessage("Message Nula");
                } else {
                    message.setMessage(message.getMessage() + " Mensagem consumida");
                }
                work.setStatus(StatusCode.OK);
            } catch (Exception e) {
                work.recordException(e);
                work.setStatus(StatusCode.ERROR, "Processing failed");
                throw e;
            } finally {
                work.end();
            }

            System.out.printf("Message: %s e host: %s%n", message.getMessage(), message.getHost());

            // 4) Build MQTT topic and publish; keep SAME trace by injecting into payload
            String mqttTopic = String.format(mqttTopicPattern, key, kafkaTopic).replace(".", "/");

            // Child PRODUCER span for MQTT publish (host/port unknown here -> leave null)
            Integer payloadBytes = (message != null && message.serialize() != null)
                    ? message.serialize().length
                    : null;
            Span mqttPublish = TracingBridge
                    .mqttProducerSpan(tracer, mqttTopic, null, null, payloadBytes)
                    .startSpan();
            try (Scope ps = mqttPublish.makeCurrent()) {
                // inject W3C context into payload so any downstream MQTT consumers can continue
                // the trace
                TracingBridge.injectIntoMessage(message);

                MqttProducer mqtt = new MqttProducer();
                mqtt.setTopic(mqttTopic);
                mqtt.produce(message);

                mqttPublish.setStatus(StatusCode.OK);
            } catch (Exception e) {
                mqttPublish.recordException(e);
                mqttPublish.setStatus(StatusCode.ERROR, "MQTT publish failed");
                throw e;
            } finally {
                mqttPublish.end();
            }

            System.out.println(message);
            receiveSpan.setStatus(StatusCode.OK);
        } catch (Exception e) {
            receiveSpan.recordException(e);
            receiveSpan.setStatus(StatusCode.ERROR, "Kafka consume -> MQTT publish failed");
            System.err.println("Erro ao processar mensagem Kafka:");
            e.printStackTrace();
        } finally {
            // Always resume even on error
            resumeConsumer("app.test.push");
            receiveSpan.end();
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
}

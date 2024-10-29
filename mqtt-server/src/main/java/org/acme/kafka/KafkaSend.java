package org.acme.kafka;

import java.util.Properties;

import org.acme.mqtt.MqttSendMessage;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class KafkaSend {

    @Inject
    Tracer tracer;

    @ConfigProperty(name = "kafka.bootstrap.server")
    private String BOOTSTRAP_SERVERS = "my-cluster-kafka-bootstrap.kafka.svc.cluster.local:9092";

    public void sendMessage(MqttSendMessage message, String key, String topic) {
        tracer = GlobalOpenTelemetry.getTracer("cons-mqtt-produce-kafka", "1.0");

        Span span = createSpan(key, topic);
        System.out.println(BOOTSTRAP_SERVERS);

        try (Producer<String, MqttSendMessage> producer = createKafkaProducer()) {

            sendRecord(producer, message, key, topic, span);

        } catch (Exception e) {
            System.err.println("Exception occurred: " + e.getMessage());
        } finally {
            span.end();
        }
    }

    private Span createSpan(String key, String topic) {
        return tracer.spanBuilder("Producer-Message-Kafka")
                .setSpanKind(SpanKind.PRODUCER)
                .setAttribute(key, topic)
                .startSpan();
    }

    private Producer<String, MqttSendMessage> createKafkaProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.acme.mqtt.MqttSendMessageSerializer");

        return new KafkaProducer<>(props);
    }

    private void sendRecord(Producer<String, MqttSendMessage> producer, MqttSendMessage message, String key,
            String topic, Span span) {
        try (Scope scope = span.makeCurrent()) {
            ProducerRecord<String, MqttSendMessage> record = new ProducerRecord<>(topic, key, message);
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    System.err.println("Error sending message: " + exception.getMessage());
                } else {
                    // Add any additional processing here
                }
            });
            producer.flush();
        }
    }
}

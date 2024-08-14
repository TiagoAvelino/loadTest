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

    @Inject
    @ConfigProperty(name = "kafka.bootstrap.server")
    private String BOOTSTRAP_SERVERS = "my-cluster-kafka-bootstrap:9092";

    public void sendMessage(MqttSendMessage message, String key, String topic) {
        tracer = GlobalOpenTelemetry.getTracer("mqtt-kafka", "1.0");

        // Start a span for this operation
        Span span = tracer.spanBuilder("Producer-Message-Kafka")
                .setSpanKind(SpanKind.PRODUCER).setAttribute(key, topic)
                .startSpan();
        // Kafka producer configuration
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.acme.mqtt.MqttSendMessageSerializer");

        // Create Kafka producer
        Producer<String, MqttSendMessage> producer = new KafkaProducer<>(props);

        try {
            // Create a message

            // Send the message to the topic
            try (Scope scope = span.makeCurrent()) {

                ProducerRecord<String, MqttSendMessage> record = new ProducerRecord<>(topic, key, message);
                producer.send(record, (metadata, exception) -> {
                    if (exception != null) {
                        System.err.println("Error sending message: " + exception.getMessage());
                    } else {
                        System.err.println("Mensagem enviada para o .push");
                    }
                });
            } finally {
                // End the span
                span.end();
            }

            // Flush and close the producer
            producer.flush();
        } catch (Exception e) {
            System.err.println("Exception occurred: " + e.getMessage());
        } finally {
            producer.close();
        }

    }
}

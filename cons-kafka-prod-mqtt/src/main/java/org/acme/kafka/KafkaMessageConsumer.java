package org.acme.kafka;

import org.acme.mqtt.MqttProducer;
import org.acme.mqtt.MqttSendMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class KafkaMessageConsumer {

    @Inject
    Tracer tracer;

    @ConfigProperty(name = "kafka.topic")
    String topic;

    public KafkaMessageConsumer() {
        // Initialize the tracer if not injected
        tracer = GlobalOpenTelemetry.getTracer("mqtt-kafka", "1.0");
    }

    @Incoming("my-channel")
    public void consume(ConsumerRecord<String, MqttSendMessage> record) {
        Span span = tracer.spanBuilder("Consume-Message-Kafka")
                .setSpanKind(SpanKind.CONSUMER)
                .startSpan();
        try (Scope scope = span.makeCurrent()) {
            MqttSendMessage message = record.value();
            String key = record.key();
            try {
                if (message == null) {
                    message = new MqttSendMessage();
                    message.setMessage("Message Nula");
                } else {
                    message.setMessage(message.getMessage() + " Mensagem consumida");
                }

                System.out.printf("Message: %s e host: %s%n", message.getMessage(), message.getHost());

                MqttProducer mqtt = new MqttProducer();
                mqtt.setTopic(transformTopic(key, topic));
                mqtt.produce(message);
                System.out.println("Continuação da mensagem");
            } catch (Exception e) {
                e.printStackTrace();
                // Handle exceptions appropriately
            }
        } finally {
            span.end();
        }
    }

    public static String transformTopic(String first, String second) {
        String combined = first + "/" + second;
        return combined.replace('.', '/');
    }
}

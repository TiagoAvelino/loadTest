package org.acme.kafka;

import org.acme.tracing.TracingBridge;
import org.acme.tracing.messageparams.MqttSendMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class KafkaMessageConsumer {

    @Inject
    KafkaSend kafkaSend;

    @Inject
    Tracer tracer;

    @Incoming("app.test")
    public MqttSendMessage consumeMessages(ConsumerRecord<String, MqttSendMessage> record) {
        final String key = record.key();
        final String topic = record.topic();
        final Headers headers = record.headers();

        // 1) Extract upstream context from headers
        Context parent = TracingBridge.extractFromKafkaHeaders(headers);

        // 2) Standard Kafka CONSUMER span using the helper
        Span receiveSpan = TracingBridge.kafkaConsumerSpan(tracer, parent, topic, key).startSpan();

        MqttSendMessage message = record.value();
        try (Scope s = receiveSpan.makeCurrent()) {
            if (message == null || message.getMessage() == null) {
                message = new MqttSendMessage();
                message.setMessage("Message Nula");
            }

            // 3) Forward with the shared producer (which injects headers); stays in same
            // trace
            String topicEnvio = topic + ".push";
            kafkaSend.sendMessage(message, key, topicEnvio);

            receiveSpan.setStatus(StatusCode.OK);
            System.out.println("Processed and forwarded message: " + message.getMessage());
            return message;
        } catch (Exception e) {
            receiveSpan.recordException(e);
            receiveSpan.setStatus(StatusCode.ERROR, "Consume/forward failed");
            throw e;
        } finally {
            receiveSpan.end();
        }
    }

    public void shutdown() {
        // no-op
    }
}

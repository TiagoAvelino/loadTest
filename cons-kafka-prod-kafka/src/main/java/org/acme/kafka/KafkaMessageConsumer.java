package org.acme.kafka;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.acme.mqtt.MqttSendMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class KafkaMessageConsumer {

    @Inject
    KafkaSend kafkaSend;

    @ConfigProperty(name = "kafka.bootstrap.server")
    String bootstrapServers;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Incoming("app.test")
    public MqttSendMessage consumeMessages(ConsumerRecord<String, MqttSendMessage> record) {
        String key = record.key(); // Can be `null` if the incoming record has no key
        String topic = record.topic();
        MqttSendMessage message = record.value();
        if (message == null || message.getMessage() == null) {
            message = new MqttSendMessage();
            message.setMessage("Message Nula");
        } else {
            message.setMessage(message.getMessage() + " - Mensagem consumida");
        }
        kafkaSend.sendMessage(message, key, topic + ".push"); // Use injected KafkaSend
        System.out.println("IP SERVER: " + message.getHost());

        System.out.println("Processed and forwarded message: " + message.getMessage());

        return message;
    }

    public static String transformTopic(String first, String second) {
        String combined = first + "/" + second;
        return combined.replace('.', '/');
    }

    public void shutdown() {
        executor.shutdown();
    }
}

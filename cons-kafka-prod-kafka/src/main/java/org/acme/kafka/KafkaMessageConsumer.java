package org.acme.kafka;

import org.acme.mqtt.MqttSendMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class KafkaMessageConsumer {

    @Inject
    KafkaSend kafkaSend;

    // @Inject
    // @Channel("app-test-window") // <── outgoing channel defined above
    // Emitter<Message<MqttSendMessage>> windowOut;
    // private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Incoming("app.test")
    public MqttSendMessage consumeMessages(ConsumerRecord<String, MqttSendMessage> record) {
        String key = record.key(); // Can be `null` if the incoming record has no key
        String topic = record.topic();
        MqttSendMessage message = record.value();
        if (message == null || message.getMessage() == null) {
            message = new MqttSendMessage();
            message.setMessage("Message Nula");
        } else {
            // message.setMessage(message.getMessage() + " - Mensagem consumida");
        }
        String topicEnvio = topic + ".push";

        kafkaSend.sendMessage(message, key, topicEnvio); // Use injected KafkaSend
        // TODO IMPLEMENTAR STREAMS
        System.out.println("Processed and forwarded message: " + message.getMessage());
        // windowOut.send(KafkaRecord.of(key, message)); // key + value in one line

        return message;
    }

    public void shutdown() {
        // executor.shutdown();
    }
}

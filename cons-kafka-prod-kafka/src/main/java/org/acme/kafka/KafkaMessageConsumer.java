package org.acme.kafka;

import org.acme.tracing.messageparams.MqttSendMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class KafkaMessageConsumer {

    @Inject
    KafkaSend kafkaSend;

    private static final Logger LOGGER = Logger.getLogger(KafkaMessageConsumer.class.getName());

    @Incoming("app.test.pull")
    public MqttSendMessage consumeMessages(ConsumerRecord<String, MqttSendMessage> record) {
        final String key = record.key();
        final String topic = record.topic();

        final long recvEpochMs = System.currentTimeMillis();

        final long recvNano = System.nanoTime();
        LOGGER.infof("Consumo kafka: sentEpochMs=%d sentNano=%d", recvEpochMs, recvNano);

        MqttSendMessage message = record.value();
        if (message == null || message.getMessage() == null) {
            message = new MqttSendMessage();
            message.setMessage("Message Nula");
        }

        // Forward with suffix change .pull -> .push
        String topicEnvio = topic.replace(".pull", ".push");
        kafkaSend.sendMessage(message, key, topicEnvio);

        return message;
    }

    public void shutdown() {
        // no-op
    }
}

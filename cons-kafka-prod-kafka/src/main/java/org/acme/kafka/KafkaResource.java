package org.acme.kafka;

import org.acme.mqtt.MqttSendMessage;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class KafkaResource {

    @Incoming("app.test")
    @Outgoing("app.test.push")
    public CompletionStage<MqttSendMessage> process(MqttSendMessage message) {
        return CompletableFuture.supplyAsync(() -> {
            message.setHost("cons-kafka-prod-kafka");
            return message;
        });
    }
}

// package org.acme.kafka;

// import java.util.concurrent.CompletableFuture;
// import java.util.concurrent.CompletionStage;

// import org.acme.tracing.messageparams.MqttSendMessage;
// import org.eclipse.microprofile.reactive.messaging.Incoming;
// import org.eclipse.microprofile.reactive.messaging.Outgoing;

// import jakarta.enterprise.context.ApplicationScoped;

// @ApplicationScoped
// public class KafkaResource {

// @Incoming("app.test.pull")
// @Outgoing("app.test.push")
// public CompletionStage<MqttSendMessage> process(MqttSendMessage message) {
// return CompletableFuture.supplyAsync(() -> {
// message.setHost("cons-kafka-prod-kafka");
// return message;
// });
// }
// }

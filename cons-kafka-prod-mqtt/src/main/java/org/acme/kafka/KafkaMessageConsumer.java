package org.acme.kafka;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.acme.mqtt.MqttProducer;
import org.acme.tracing.messageparams.MqttSendMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import io.smallrye.reactive.messaging.kafka.KafkaClientService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class KafkaMessageConsumer {
    private static final Logger LOGGER = Logger.getLogger(KafkaMessageConsumer.class.getName());

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

        final long recvEpochMs = System.currentTimeMillis();

        final long recvNano = System.nanoTime();
        LOGGER.infof("Consumo kafka: sentEpochMs=%d sentNano=%d", recvEpochMs, recvNano);

        try {
            // (optional) pause/resume as part of processing window
            pauseConsumer("app.test.push");

            // Normalize/process message
            MqttSendMessage message = record.value();
            if (message == null) {
                message = new MqttSendMessage();
                message.setMessage("Message Nula");
            } else {
                message.setMessage(message.getMessage() + " Mensagem consumida");
            }

            System.out.printf("Message: %s e host: %s%n", message.getMessage(), message.getHost());

            // Build MQTT topic and publish
            String mqttTopic = String.format(mqttTopicPattern, key, kafkaTopic).replace(".", "/");

            MqttProducer mqtt = new MqttProducer();
            mqtt.setTopic(mqttTopic);
            mqtt.produce(message);

            System.out.println(message);
        } catch (Exception e) {
            System.err.println("Erro ao processar mensagem Kafka:");
            e.printStackTrace();
        } finally {
            // Always resume even on error
            resumeConsumer("app.test.push");
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

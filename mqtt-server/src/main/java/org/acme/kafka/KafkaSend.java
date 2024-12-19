package org.acme.kafka;

import org.acme.mqtt.MqttSendMessage;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class KafkaSend {

    private static final Logger LOGGER = Logger.getLogger(KafkaSend.class.getName());

    @ConfigProperty(name = "kafka.bootstrap.server")
    String bootstrapServers;

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void sendMessage(MqttSendMessage message, String key, String topic) {
        LOGGER.info(() -> String.format("Preparing to send message to Kafka. Topic: %s, Key: %s, Message: %s", topic,
                key, message));
        System.out.println(topic);

        try (Producer<String, MqttSendMessage> producer = createKafkaProducer()) {
            sendRecord(producer, message, key, topic);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Exception occurred while sending message to Kafka", e);
        }
    }

    private Producer<String, MqttSendMessage> createKafkaProducer() {
        LOGGER.info("Creating Kafka producer with bootstrap servers: " + bootstrapServers);
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.acme.mqtt.MqttSendMessageSerializer");

        return new KafkaProducer<>(props);
    }

    private void sendRecord(Producer<String, MqttSendMessage> producer, MqttSendMessage message, String key,
            String topic) {
        LOGGER.info(
                () -> String.format("Sending record to Kafka. Topic: %s, Key: %s, Message: %s", topic, key, message));
        ProducerRecord<String, MqttSendMessage> record = new ProducerRecord<>(topic, key, message);
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                LOGGER.log(Level.SEVERE, "Error sending message to Kafka", exception);
            } else {
                LOGGER.info(() -> String.format("Message sent successfully. Topic: %s, Partition: %d, Offset: %d",
                        metadata.topic(), metadata.partition(), metadata.offset()));
            }
        });
        producer.flush();
    }
}

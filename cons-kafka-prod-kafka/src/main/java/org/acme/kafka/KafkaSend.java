package org.acme.kafka;

import java.util.Properties;
import java.util.concurrent.Future;

import org.acme.mqtt.MqttSendMessage;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class KafkaSend {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    private KafkaProducer<String, MqttSendMessage> kafkaProducer;

    @PostConstruct
    public void initialize() {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", "org.acme.mqtt.MqttSendMessageSerializer"); // Custom serializer
        props.put("acks", "all"); // Ensure message durability
        kafkaProducer = new KafkaProducer<>(props);
    }

    public void sendMessage(MqttSendMessage message, String key, String topic) {
        try {
            ProducerRecord<String, MqttSendMessage> record = new ProducerRecord<>(topic, key, message);
            Future<RecordMetadata> future = kafkaProducer.send(record);
            RecordMetadata metadata = future.get();
            System.out.println(
                    "Message sent successfully to topic: " + metadata.topic() + " at offset: " + metadata.offset());
        } catch (Exception e) {
            System.err.println("Failed to send message: " + e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        if (kafkaProducer != null) {
            kafkaProducer.close();
        }
    }
}

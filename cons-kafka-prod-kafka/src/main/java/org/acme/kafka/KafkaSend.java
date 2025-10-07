package org.acme.kafka;

import java.util.Properties;
import java.util.concurrent.Future;

import org.acme.tracing.messageparams.MqttSendMessage;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class KafkaSend {
    private static final Logger LOGGER = Logger.getLogger(KafkaSend.class.getName());

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @ConfigProperty(name = "kafka.security.protocol", defaultValue = "PLAINTEXT")
    String securityProtocol;

    @ConfigProperty(name = "kafka.sasl.mechanism", defaultValue = "")
    String saslMechanism;

    @ConfigProperty(name = "kafka.sasl.jaas.config", defaultValue = "")
    String jaasConfig;

    @ConfigProperty(name = "kafka.ssl.truststore.location", defaultValue = "")
    String truststoreLocation;

    @ConfigProperty(name = "kafka.ssl.truststore.password", defaultValue = "")
    String truststorePassword;

    private KafkaProducer<String, MqttSendMessage> kafkaProducer;

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    @PostConstruct
    public void initialize() {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", "org.acme.tracing.messageparams.MqttSendMessageSerializer"); // custom serializer
        props.put("acks", "all");

        if (!isBlank(securityProtocol))
            props.put("security.protocol", securityProtocol);
        if (!isBlank(saslMechanism))
            props.put("sasl.mechanism", saslMechanism);
        if (!isBlank(jaasConfig))
            props.put("sasl.jaas.config", jaasConfig);
        if (!isBlank(truststoreLocation))
            props.put("ssl.truststore.location", truststoreLocation);
        if (!isBlank(truststorePassword))
            props.put("ssl.truststore.password", truststorePassword);

        kafkaProducer = new KafkaProducer<>(props);
    }

    /** Backward-compatible: no header, no key */
    public void sendMessage(MqttSendMessage message, String topic) {
        sendMessage(message, null, topic, null);
    }

    /** New: attach key and the x-mqtt-topic header to the outgoing record */
    public void sendMessage(MqttSendMessage message, String key, String topic, String mqttTopicHeader) {
        try {
            ProducerRecord<String, MqttSendMessage> record = new ProducerRecord<>(topic, key, message);
            if (mqttTopicHeader != null && !mqttTopicHeader.isBlank()) {
                record.headers().remove("x-mqtt-topic");
                record.headers().add("x-mqtt-topic", mqttTopicHeader.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            Future<RecordMetadata> future = kafkaProducer.send(record);
            RecordMetadata metadata = future.get();

            final long nowMs = System.currentTimeMillis();
            final long nowNs = System.nanoTime();
            LOGGER.infof("Envio kafka: sentEpochMs=%d sentNano=%d", nowMs, nowNs);
            LOGGER.infof("Kafka message sent: topic=%s, partition=%d, offset=%d",
                    metadata.topic(), metadata.partition(), metadata.offset());
        } catch (Exception e) {
            LOGGER.errorf("Failed to send Kafka message: %s", e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        if (kafkaProducer != null) {
            kafkaProducer.close();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

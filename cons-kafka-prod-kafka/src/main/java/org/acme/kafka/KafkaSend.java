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

        // Optional security
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

        // (Optional) throughput-friendly defaults; uncomment if you want
        // batching/compression
        // props.putIfAbsent("linger.ms", "5");
        // props.putIfAbsent("batch.size", String.valueOf(32 * 1024));
        // props.putIfAbsent("compression.type", "lz4");

        kafkaProducer = new KafkaProducer<>(props);
    }

    public void sendMessage(MqttSendMessage message, String key, String topic) {
        try {
            ProducerRecord<String, MqttSendMessage> record = new ProducerRecord<>(topic, key, message);

            // Synchronous send (simple & predictable)
            Future<RecordMetadata> future = kafkaProducer.send(record);
            RecordMetadata metadata = future.get();

            final long recvEpochMs = System.currentTimeMillis();

            final long recvNano = System.nanoTime();
            LOGGER.infof("Envio kafka: sentEpochMs=%d sentNano=%d", recvEpochMs, recvNano);

            LOGGER.infof("Kafka message sent: topic=%s, partition=%d, offset=%d%n",
                    metadata.topic(), metadata.partition(), metadata.offset());
        } catch (Exception e) {
            LOGGER.errorf("Failed to send Kafka message: " + e.getMessage());
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

package org.acme.kafka;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.jboss.logging.Logger;

import org.acme.tracing.messageparams.MqttSendMessage;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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

    private final AtomicReference<Producer<String, MqttSendMessage>> producerRef = new AtomicReference<>();

    @PostConstruct
    void init() {
        ensureProducer();
    }

    @PreDestroy
    void shutdown() {
        Producer<String, MqttSendMessage> p = producerRef.getAndSet(null);
        if (p != null) {
            try {
                p.flush();
                p.close();
                LOGGER.info("Kafka producer closed");
            } catch (Exception e) {
                LOGGER.warn("Error closing Kafka producer", e);
            }
        }
    }

    public void sendMessage(MqttSendMessage message, String key, String topic) {
        try {
            Producer<String, MqttSendMessage> producer = ensureProducer();
            ProducerRecord<String, MqttSendMessage> record = new ProducerRecord<>(topic, key, message);

            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    LOGGER.error("Error sending message to Kafka", exception);
                } else {

                    final long recvEpochMs = System.currentTimeMillis();

                    final long recvNano = System.nanoTime();
                    LOGGER.infof("Envio kafka: sentEpochMs=%d sentNano=%d", recvEpochMs, recvNano);
                }
            });

            producer.flush();
        } catch (Exception e) {
            LOGGER.warn("Exception in KafkaSend.sendMessage", e);
        }
    }

    private Producer<String, MqttSendMessage> ensureProducer() {
        Producer<String, MqttSendMessage> existing = producerRef.get();
        if (existing != null)
            return existing;

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.acme.tracing.messageparams.MqttSendMessageSerializer");

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

        props.putIfAbsent(ProducerConfig.ACKS_CONFIG, "all");
        props.putIfAbsent(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.putIfAbsent(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(32 * 1024));
        props.putIfAbsent(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        Producer<String, MqttSendMessage> created = new KafkaProducer<>(props);
        if (producerRef.compareAndSet(null, created)) {
            LOGGER.infof("Kafka producer created (bootstrap=%s)", bootstrapServers);
            return created;
        } else {
            created.close();
            return producerRef.get();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

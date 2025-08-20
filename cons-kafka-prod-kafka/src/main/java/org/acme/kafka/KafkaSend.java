package org.acme.kafka;

import java.util.Properties;
import java.util.concurrent.Future;

import org.acme.tracing.TracingBridge; // <-- shared tracing lib
import org.acme.tracing.messageparams.MqttSendMessage;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class KafkaSend {

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

    @Inject
    Tracer tracer;

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

    public void sendMessage(MqttSendMessage message, String key, String topic) {
        // 1. Start PRODUCER span
        Span span = TracingBridge.kafkaProducerSpan(tracer, topic, key, bootstrapServers).startSpan();
        try (Scope scope = span.makeCurrent()) {
            // 2. Create record
            ProducerRecord<String, MqttSendMessage> record = new ProducerRecord<>(topic, key, message);

            // 3. Inject W3C trace context into headers
            TracingBridge.injectIntoKafkaHeaders(record.headers());

            // 4. Send synchronously (blocking get for demo; in prod you may prefer async)
            Future<RecordMetadata> future = kafkaProducer.send(record);
            RecordMetadata metadata = future.get();

            span.setAttribute("messaging.kafka.partition", metadata.partition());
            span.setAttribute("messaging.kafka.offset", metadata.offset());
            span.setStatus(StatusCode.OK);

            System.out.printf("Kafka message sent: topic=%s, partition=%d, offset=%d%n",
                    metadata.topic(), metadata.partition(), metadata.offset());
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, "Kafka send failed");
            System.err.println(" Failed to send Kafka message: " + e.getMessage());
        } finally {
            span.end();
        }
    }

    @PreDestroy
    public void close() {
        if (kafkaProducer != null) {
            kafkaProducer.close();
            System.out.println("Kafka producer closed");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

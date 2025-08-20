package org.acme.kafka;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.acme.tracing.TracingBridge;
import org.acme.tracing.messageparams.MqttSendMessage;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class KafkaSend {

    private static final Logger LOGGER = Logger.getLogger(KafkaSend.class.getName());

    @Inject
    Tracer tracer;

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

    // Reuse a single producer for performance
    private final AtomicReference<Producer<String, MqttSendMessage>> producerRef = new AtomicReference<>();

    // OpenTelemetry header setter for Kafka (used by
    // TracingBridge.injectIntoKafkaHeaders)
    private static final TextMapSetter<Headers> KAFKA_HEADERS_SETTER = (headers, key, value) -> {
        if (headers != null && key != null && value != null) {
            // Use standard W3C keys: "traceparent", "tracestate"
            headers.remove(key); // avoid duplicates if retried
            headers.add(key, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    };

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
                LOGGER.log(Level.WARNING, "Error closing Kafka producer", e);
            }
        }
    }

    public void sendMessage(MqttSendMessage message, String key, String topic) {
        // Standardized PRODUCER span via TracingBridge
        Span sendSpan = TracingBridge.kafkaProducerSpan(tracer, topic, key, bootstrapServers).startSpan();

        try (Scope scope = sendSpan.makeCurrent()) {
            Producer<String, MqttSendMessage> producer = ensureProducer();

            // Create record and inject W3C context into Kafka headers
            ProducerRecord<String, MqttSendMessage> record = new ProducerRecord<>(topic, key, message);
            GlobalOpenTelemetry.getPropagators()
                    .getTextMapPropagator()
                    .inject(io.opentelemetry.context.Context.current(), record.headers(), KAFKA_HEADERS_SETTER);
            // (Alternatively) TracingBridge.injectIntoKafkaHeaders(record.headers());

            long start = System.nanoTime();
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    sendSpan.recordException(exception);
                    sendSpan.setStatus(StatusCode.ERROR, "Send failed");
                    LOGGER.log(Level.SEVERE, "Error sending message to Kafka", exception);
                } else {
                    sendSpan.setAttribute("messaging.kafka.partition", metadata.partition());
                    sendSpan.setAttribute("messaging.kafka.offset", metadata.offset());
                    sendSpan.setAttribute(TracingBridge.ATTR_LATENCY_MS, (System.nanoTime() - start) / 1_000_000.0);
                    LOGGER.info(() -> String.format(
                            "Message sent. Topic=%s, Partition=%d, Offset=%d",
                            metadata.topic(), metadata.partition(), metadata.offset()));
                }
            });

            // flush improves timeliness of spans in UIs; remove if you prefer throughput
            producer.flush();
            sendSpan.setStatus(StatusCode.OK);
        } catch (Exception e) {
            sendSpan.recordException(e);
            sendSpan.setStatus(StatusCode.ERROR, "Producer send error");
            LOGGER.log(Level.SEVERE, "Exception in KafkaSend.sendMessage", e);
        } finally {
            sendSpan.end();
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

        // Security (optional / environment-dependent)
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

        // Useful producer perf settings (safe defaults)
        props.putIfAbsent(ProducerConfig.ACKS_CONFIG, "all");
        props.putIfAbsent(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.putIfAbsent(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(32 * 1024)); // 32KB
        props.putIfAbsent(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        Producer<String, MqttSendMessage> created = new KafkaProducer<>(props);
        if (producerRef.compareAndSet(null, created)) {
            LOGGER.info(() -> String.format("Kafka producer created (bootstrap=%s)", bootstrapServers));
            return created;
        } else {
            // Another thread won the race
            created.close();
            return producerRef.get();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

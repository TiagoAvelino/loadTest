package org.acme.kafka;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.acme.mqtt.MqttSendMessage;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class KafkaSend {

    private static final Logger LOGGER = Logger.getLogger(KafkaSend.class.getName());

    @Inject
    Tracer tracer;

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;
    @ConfigProperty(name = "kafka.security.protocol")
    String securityProtocol;
    @ConfigProperty(name = "kafka.sasl.mechanism")
    String saslMechanism;
    @ConfigProperty(name = "kafka.sasl.jaas.config")
    String jaasConfig;
    @ConfigProperty(name = "kafka.ssl.truststore.location")
    String truststoreLocation;
    @ConfigProperty(name = "kafka.ssl.truststore.password")
    String truststorePassword;

    public void sendMessage(MqttSendMessage message, String key, String topic) {
        // 1. Start a producer-span
        Span producerSpan = tracer.spanBuilder("KafkaProducer.create")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        try (Scope ignored = producerSpan.makeCurrent()) {
            producerSpan.setAttribute("messaging.system", "kafka");
            producerSpan.setAttribute("messaging.destination", topic);
            producerSpan.setAttribute("messaging.kafka.bootstrap_servers", bootstrapServers);

            LOGGER.info(() -> String.format("Creating Kafka producer (bootstrap=%s)", bootstrapServers));
            Producer<String, MqttSendMessage> producer = createKafkaProducer();
            producerSpan.end();

            // 2. Start send-record span
            Span sendSpan = tracer.spanBuilder("KafkaProducer.send")
                    .setSpanKind(SpanKind.PRODUCER)
                    .startSpan();
            try (Scope sendScope = sendSpan.makeCurrent()) {
                sendSpan.setAttribute("messaging.system", "kafka");
                sendSpan.setAttribute("messaging.destination_kind", "topic");
                sendSpan.setAttribute("messaging.destination", topic);
                sendSpan.setAttribute("messaging.kafka.message_key", key);

                LOGGER.info(() -> String.format("Sending record. Topic=%s, Key=%s, Message=%s", topic, key, message));
                ProducerRecord<String, MqttSendMessage> record = new ProducerRecord<>(topic, key, message);
                producer.send(record, (metadata, exception) -> {
                    if (exception != null) {
                        sendSpan.recordException(exception);
                        sendSpan.setStatus(StatusCode.ERROR, "Send failed");
                        LOGGER.log(Level.SEVERE, "Error sending message to Kafka", exception);
                    } else {
                        sendSpan.setAttribute("messaging.kafka.partition", metadata.partition());
                        sendSpan.setAttribute("messaging.kafka.offset", metadata.offset());
                        LOGGER.info(() -> String.format(
                                "Message sent. Topic=%s, Partition=%d, Offset=%d",
                                metadata.topic(), metadata.partition(), metadata.offset()));
                    }
                });
                producer.flush();
            } catch (Exception e) {
                sendSpan.recordException(e);
                sendSpan.setStatus(StatusCode.ERROR, "Send error");
                throw e;
            } finally {
                sendSpan.end();
                producer.close();
            }
        } catch (Exception e) {
            producerSpan.recordException(e);
            producerSpan.setStatus(StatusCode.ERROR, "Producer creation or send failed");
            LOGGER.log(Level.SEVERE, "Exception in KafkaSend.sendMessage", e);
        } finally {
            producerSpan.end();
        }
    }

    private Producer<String, MqttSendMessage> createKafkaProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.acme.mqtt.MqttSendMessageSerializer");
        props.put("security.protocol", securityProtocol);
        props.put("sasl.mechanism", saslMechanism);
        props.put("sasl.jaas.config", jaasConfig);
        props.put("ssl.truststore.location", truststoreLocation);
        props.put("ssl.truststore.password", truststorePassword);

        return new KafkaProducer<>(props);
    }
}

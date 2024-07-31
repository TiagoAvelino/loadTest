package org.acme.kafka;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.acme.mqtt.MqttProducer;
import org.acme.mqtt.MqttSendMessage;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class KafkaMessageConsumer {

    @Inject
    Tracer tracer;
    private final KafkaConsumer<String, MqttSendMessage> consumer;

    @ConfigProperty(name = "kafka.bootstrap.server")
    private String BOOTSTRAP_SERVERS = "my-cluster-kafka-bootstrap:9092";
    Map<String, List<PartitionInfo>> topics;
    private String topic = "";

    public KafkaMessageConsumer() {
        this.consumer = null;
    }

    public KafkaMessageConsumer(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "my-group"); // Specify a consumer group
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.acme.mqtt.MqttSendMessageDeserializerKafka"); // Implement
        // your
        // deserializer
        consumer = new KafkaConsumer<>(props);

        this.topic = topic;
        System.out.println(topic);
        consumeMessages();

    }

    public void consumeMessages() {
        tracer = GlobalOpenTelemetry.getTracer("mqtt-kafka", "1.0");

        consumer.subscribe(Collections.singleton(topic));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        System.out.println("Começando a consumir do topico: " + topic);

        executor.execute(() -> {
            while (true) {
                ConsumerRecords<String, MqttSendMessage> records = consumer.poll(Duration.ofMillis(100)); // Adjust poll
                // duration as
                // needed

                records.forEach(record -> {
                    Span span = tracer.spanBuilder("Consume-Message-Kafka")
                            .setSpanKind(SpanKind.PRODUCER).setAttribute(record.key(), topic)
                            .startSpan();
                    try (Scope scope = span.makeCurrent()) {
                        MqttSendMessage messageMqtt = new MqttSendMessage();
                        if (record.value().equals(null)) {
                            record.value().setMessage("Message Nula");

                        } else {
                            messageMqtt = record.value();

                        }
                        if (topic.endsWith(".push")) {

                            messageMqtt.setMessage(messageMqtt.getMessage() + "Mensagem consumida");

                            System.out.printf("Message com push: %s e host: %s ", messageMqtt.getMessage(),
                                    messageMqtt.getHost());

                            MqttProducer mqtt = new MqttProducer();
                            mqtt.setTopic(transformTopic(record.key(), topic));
                            mqtt.Produce(messageMqtt);
                        } else {

                            messageMqtt.setMessage(messageMqtt.getMessage() + "- Mensagem consumida");
                            System.out.println("Enviando para o kafka" + messageMqtt.getHost());
                            new KafkaSend().sendMessage(messageMqtt, record.key(), topic + ".push");

                        }

                        // Process the received message here
                        // System.out.println("Received message: " + message.getMessage());
                    } finally {
                        // End the span
                        span.end();
                    }
                });
            }
        });
    }

    public void close() {
        consumer.close();
    }

    public static String transformTopic(String first, String second) {
        String combined = first + "/" + second;

        combined = combined.replace('.', '/');
        return combined;
    }

}
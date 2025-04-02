package org.acme.mqttBroker;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.acme.kafka.KafkaSend;
import org.acme.mqtt.MqttSendMessage;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.jboss.logging.Logger;

import io.opentelemetry.api.trace.Tracer;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@Startup(20)
@RegisterForReflection
@ApplicationScoped
public class MqttConsumerService {

    private static final Logger LOGGER = Logger.getLogger(MqttConsumerService.class);

    @Inject
    Tracer tracer;

    @Inject
    KafkaSend producer;

    private IMqttClient client;

    @Inject
    ManagedExecutor managedExecutor;

    public void onStart(@Observes StartupEvent ev) {
        LOGGER.info("Starting MQTT Consumer Service...");
        init();
    }

    public void init() {
        try {
            client = new MqttClient("tcp://localhost:1883", "1");
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);

            client.connect(options);
            LOGGER.info("Connected to MQTT broker for consuming");

            client.subscribe("#", (topic, message) -> {
                try {
                    LOGGER.info("Received message on topic: " + topic);

                    if (topic.endsWith("/push")) {
                        LOGGER.info("Message received on push topic: " + topic);
                    } else {
                        MqttSendMessage receivedMessage = deserialize(message.getPayload());
                        receivedMessage = getHostIp(receivedMessage);
                        processMessage(receivedMessage);
                        LOGGER.info("TOPIC MQTT: " + topic);

                        String transformedKey = transformKey(topic);
                        String transformedTopic = transformTopic(topic);

                        LOGGER.info("Transformed Key: " + transformedKey);
                        LOGGER.info("Transformed Topic: " + transformedTopic);
                        producer.sendMessage(receivedMessage, transformedKey, transformedTopic);
                    }
                } catch (Exception e) {
                    LOGGER.error("Error processing message from topic: " + topic, e);
                }
            });
        } catch (MqttException e) {
            LOGGER.error("Failed to connect or subscribe to MQTT broker", e);
        }
    }

    private void processMessage(MqttSendMessage message) {
        LOGGER.info("Processing message in first-consumer: " + message.getMessage());
        LOGGER.info("Processing message in first-consumer: " + message.getHost());
    }

    private MqttSendMessage getHostIp(MqttSendMessage message) {
        try {
            InetAddress inetAddress = InetAddress.getLocalHost();
            message.setHost(inetAddress.getHostAddress());
            return message;
        } catch (UnknownHostException e) {
            LOGGER.warn("Failed to retrieve host IP", e);
            return message;
        }
    }

    private MqttSendMessage deserialize(byte[] data) {
        try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
                ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream)) {
            return (MqttSendMessage) objectInputStream.readObject();
        } catch (IOException | ClassNotFoundException e) {
            LOGGER.error("Failed to deserialize message payload", e);
            return null;
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (client != null) {
                client.disconnect();
                client.close();
                LOGGER.info("Disconnected from MQTT broker");
            }
        } catch (MqttException e) {
            LOGGER.error("Failed to disconnect from MQTT broker", e);
        }
    }

    public static String transformKey(String mqttTopic) {
        if (mqttTopic == null || mqttTopic.split("/").length < 4) {
            throw new IllegalArgumentException("Invalid input format: The string must have at least 3 '/' characters.");
        }

        // Split the topic into parts
        String[] parts = mqttTopic.split("/", 4); // At most 4 parts

        // Join the first three parts and replace "/" with "."
        String beforeThirdSlash = String.join("/", parts[0], parts[1], parts[2]);
        return beforeThirdSlash.replace("/", ".");
    }

    public static String transformTopic(String mqttTopic) {
        if (mqttTopic == null || mqttTopic.split("/").length < 4) {
            throw new IllegalArgumentException("Invalid input format: The string must have at least 3 '/' characters.");
        }

        // Split the topic into parts
        String[] parts = mqttTopic.split("/", 4); // At most 4 parts

        // Get the part after the third slash and replace "/" with "."
        String afterThirdSlash = parts[3];
        return afterThirdSlash.replace("/", ".");
    }
}
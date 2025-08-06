package org.acme.mqtt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.jboss.logging.Logger;

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
public class MqttConsumer {
    @ConfigProperty(name = "quarkus.openshift.env.vars.service")
    private String broker;

    private static final Logger LOGGER = Logger.getLogger(MqttConsumer.class);
    private IMqttClient client;

    @Inject
    ManagedExecutor managedExecutor;

    public void onStart(@Observes StartupEvent ev) {
        LOGGER.info("Starting MQTT Consumer Service...");
        init();
    }

    public void init() {
        try {
            client = new MqttClient(broker, "user", new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);

            client.connect(options);
            LOGGER.info("Connect to MQTT broker for consuming");

            client.subscribe("mqtt-message-in/1/2/app/test/push", (topic, message) -> {
                try {
                    LOGGER.info("Received message on topic-MOBILE: " + topic);
                    MqttSendMessage receivedMessage = deserialize(message.getPayload());
                    processMessage(receivedMessage);
                } catch (Exception e) {
                    LOGGER.error("Error processing message from topic: " + topic, e);
                }
            });
        } catch (MqttException e) {
            LOGGER.error("Failed to connect or subscribe to MQTT broker", e);
        }
    }

    private void processMessage(MqttSendMessage message) {
        LOGGER.info("Message received:" + message.getMessage());
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
}

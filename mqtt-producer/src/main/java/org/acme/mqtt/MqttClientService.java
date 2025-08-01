package org.acme.mqtt;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.jboss.logging.Logger;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MqttClientService {

    private IMqttClient client;

    @ConfigProperty(name = "quarkus.openshift.env.vars.service")
    private String broker;

    private volatile boolean connecting;
    private static final Logger logger = Logger.getLogger(MqttClientService.class);

    public void init(String topic) {
        connectAndSubscribe(topic);
    }

    private synchronized void connectAndSubscribe(String topic) {
        if (client == null || !client.isConnected()) {
            try {
                logger.info("Connecting to: " + broker + " Mqtt Server");
                client = new MqttClient(broker, MqttClient.generateClientId(), new MemoryPersistence());
                MqttConnectOptions connOpts = new MqttConnectOptions();
                connOpts.setCleanSession(true);
                connOpts.setMaxInflight(1000);

                connecting = true;
                IMqttToken token = client.connectWithResult(connOpts);
                token.waitForCompletion();
                connecting = false;

                logger.info("Connected to the: " + broker + " Mqtt Server");

                client.setCallback(getCallback(topic));
                logger.info("Subscribing to topic: " + topic);
                client.subscribe(topic, 0);
                logger.info("Successfully subscribed to topic: " + topic);
            } catch (MqttException me) {
                connecting = false;
                logger.error("Error while connecting or subscribing to MQTT broker: ", me);
            }
        }
    }

    public void publishMessage(String topic, MqttSendMessage payload) {
        try {
            ensureConnected(topic);

            byte[] data = payload.serialize();
            logger.infof("Publishing message. Size: %d bytes", data.length);

            MqttMessage message = new MqttMessage(data);
            message.setQos(0); // optionally test with 0 for faster throughput

            long start = System.nanoTime();
            client.publish(topic, message);
            long end = System.nanoTime();

            logger.infof("Published in %.2f ms", (end - start) / 1_000_000.0);
        } catch (MqttException | InterruptedException e) {
            logger.error("Error while publishing message to MQTT broker: ", e);
        }
    }

    private synchronized void ensureConnected(String topic) throws InterruptedException {
        if (client == null || !client.isConnected()) {
            connectAndSubscribe(topic);
        }

        int retries = 0;
        while (connecting && retries < 50) {
            Thread.sleep(10); // reduced from 100ms to 10ms for quicker readiness
            retries++;
        }

        if (!client.isConnected()) {
            throw new IllegalStateException("Failed to connect to MQTT broker after retries");
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (client != null) {
                client.disconnect();
                client.close();
                logger.info("Disconnected from MQTT broker");
            }
        } catch (MqttException e) {
            logger.error("Error while disconnecting MQTT client", e);
        }
    }

    private MqttCallback getCallback(String topic) {
        return new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                logger.warn("Connection lost: " + cause.getMessage());
                // Attempt reconnect
                connectAndSubscribe(topic);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                long receivedAt = System.nanoTime();
                logger.info("Message arrived. Topic: " + topic + " Size: " + message.getPayload().length + " bytes at "
                        + receivedAt);
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                logger.debug("Delivery complete for token: " + token.getMessageId());
            }
        };
    }
}

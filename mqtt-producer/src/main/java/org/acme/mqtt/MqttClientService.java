package org.acme.mqtt;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
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
                client = new MqttClient(broker, MqttClient.generateClientId());
                MqttConnectOptions connOpts = new MqttConnectOptions();
                connOpts.setCleanSession(true);
                connOpts.setMaxInflight(1000);
                connecting = true;
                IMqttToken token = client.connectWithResult(connOpts);
                token.waitForCompletion();
                connecting = false;
                logger.info("Connected to the: " + broker + " Mqtt Server");
                client.setCallback(getCallback(topic));
                logger.info("Subscribe to the Topic: " + topic);
                client.subscribe(topic, 1);
                logger.info("Successful subscription to topic: " + topic);
            } catch (MqttException me) {
                connecting = false;
                logger.error("Error while connecting or subscribing to MQTT broker: ", me);
            }
        }
    }

    public void publishMessage(String topic, MqttSendMessage payload) {
        try {
            ensureConnected(topic);
            MqttMessage message = new MqttMessage(payload.serialize());
            message.setQos(1);
            client.publish(topic, message);
        } catch (MqttException | InterruptedException e) {
            logger.error("Error while publishing message to MQTT broker: ", e);
        }
    }

    private synchronized void ensureConnected(String topic) throws InterruptedException {
        if (client == null || !client.isConnected()) {
            connectAndSubscribe(topic);
        }
        while (connecting) {
            Thread.sleep(100);
        }
        if (!client.isConnected()) {
            throw new IllegalStateException("Failed to connect to MQTT broker");
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (client != null) {
                client.disconnect();
                client.close();
                System.out.println("Disconnected from MQTT broker");
            }
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    private MqttCallback getCallback(String topic) {
        return new MqttCallback() {

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // TODO Auto-generated method stub
                logger.warn("Connection lost: ");
                // Attempt to reconnect
                connectAndSubscribe(topic);
            }

            @Override
            public void connectionLost(Throwable cause) {
                // TODO Auto-generated method stub
                throw new UnsupportedOperationException("Unimplemented method 'connectionLost'");
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                // TODO Auto-generated method stub
                logger.info("Message arrived. Topic: " + topic + " Message: " + new String(message.getPayload()));
            }
        };
    }
}
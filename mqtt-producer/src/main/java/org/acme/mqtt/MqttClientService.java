package org.acme.mqtt;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MqttClientService {

    private IMqttClient client;
    @ConfigProperty(name = "quarkus.openshift.env.vars.service")
    private String broker;

    // @PostConstruct
    // public void init() {
    // try {

    // client = new MqttClient(broker, MqttClient.generateClientId());
    // MqttConnectOptions options = new MqttConnectOptions();
    // options.setCleanSession(true);
    // client.connect(options);
    // System.out.println("Connected to MQTT broker");

    // // client.subscribe("test/topic", (topic, message) -> {
    // // System.out.println("Received message: " + new
    // String(message.getPayload()));
    // // });
    // } catch (MqttException e) {
    // e.printStackTrace();
    // }
    // }

    public void publishMessage(String topic, MqttSendMessage payload) {
        try {

            client = new MqttClient(broker, MqttClient.generateClientId());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            client.connect(options);
            System.out.println("Connected to MQTT broker");
            MqttMessage message = new MqttMessage(payload.serialize());
            message.setQos(1);
            client.publish(topic, message);
            client.disconnect();

            client.close();
        } catch (MqttException e) {
            e.printStackTrace();
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
}
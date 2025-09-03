package org.acme.mqtt;

import java.time.temporal.ChronoUnit;

import org.acme.tracing.messageparams.MqttSendMessage;

import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;

import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MqttProducer {

    private static final String MQTT_BROKER_PREFIX = "tcp://";
    private static final int MQTT_DEFAULT_PORT = 1883;

    // Keep the same “simple” style: connect -> publish -> disconnect
    // QoS explicit for clarity: 0 = at most once; fastest.
    private static final int qos = 0;

    private String topic = "";

    public String getTopic() {
        return this.topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    // The method will timeout if it takes longer than 100 ms.
    @Timeout(value = 100, unit = ChronoUnit.MILLIS)
    public void produce(MqttSendMessage mqttMes) {
        final String brokerHost = mqttMes.getHost();
        final String brokerUrl = MQTT_BROKER_PREFIX + brokerHost + ":" + MQTT_DEFAULT_PORT;
        final String destTopic = this.topic;

        System.out.printf("Preparing to send message to topic: %s and host: %s%n", destTopic, brokerUrl);

        // Serialize once, reuse for publish
        byte[] payloadBytes = mqttMes.serialize();

        try {
            IMqttClient mqttClient = new MqttClient(brokerUrl, MqttClient.generateClientId(), new MemoryPersistence());
            MqttConnectOptions connectOptions = new MqttConnectOptions();
            connectOptions.setConnectionTimeout(10); // seconds
            connectOptions.setKeepAliveInterval(30);
            connectOptions.setCleanSession(true);

            mqttClient.connect(connectOptions);

            long start = System.nanoTime();
            mqttClient.publish(destTopic, payloadBytes, qos, false);
            long end = System.nanoTime();

            System.out.printf("Published to MQTT in %.3f ms%n", (end - start) / 1_000_000.0);

            mqttClient.disconnect();
            mqttClient.close();
        } catch (TimeoutException e) {
            System.err.printf("Timeout to publish message to MQTT broker: %s%n", e.getMessage());
        } catch (MqttException e) {
            System.err.printf("Failed to publish message to MQTT broker: %s %s %s%n",
                    e.getMessage(), e.getCause(), e.getReasonCode());
        } catch (Exception e) {
            System.err.printf("Unexpected error publishing to MQTT: %s%n", e.getMessage());
        }
    }
}

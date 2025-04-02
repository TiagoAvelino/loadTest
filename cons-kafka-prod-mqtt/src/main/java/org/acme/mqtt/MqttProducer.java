package org.acme.mqtt;

import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

import org.acme.health.IpHealthChecker;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MqttProducer {

    private static final String MQTT_BROKER_PREFIX = "tcp://";

    private String topic = "";

    @Inject
    Tracer tracer;

    private final IpHealthChecker ipHealthChecker = new IpHealthChecker();

    public String getTopic() {
        return this.topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    // The method will timeout if it takes longer than 30 seconds.
    @Timeout(value = 12, unit = ChronoUnit.SECONDS)
    public void produce(MqttSendMessage mqttMes) {
        tracer = GlobalOpenTelemetry.getTracer("mqtt-kafka", "1.0");

        System.out.printf("Preparing to send message to topic: %s and host: %s%n",
                topic, MQTT_BROKER_PREFIX + mqttMes.getHost() + ":1883");

        try {
            MqttClient mqttClient = new MqttClient(
                    MQTT_BROKER_PREFIX + mqttMes.getHost() + ":1883",
                    MqttClient.generateClientId());
            MqttConnectOptions connectOptions = new MqttConnectOptions();
            connectOptions.setConnectionTimeout(10); // Connection timeout for establishing the link
            connectOptions.setKeepAliveInterval(30); // Optional: Set keep-alive interval
            mqttClient.connect(connectOptions);
            System.out.println("Connected to MQTT broker.");

            MqttMessage mqttMessage = new MqttMessage();
            mqttMessage.setPayload(mqttMes.serialize());

            mqttClient.publish(topic, mqttMessage);
            System.out.println("Message published successfully.");

            mqttClient.disconnect();
            System.out.println("Disconnected from MQTT broker.");
        } catch (MqttException e) {
            System.err.printf("Failed to publish message to MQTT broker: %s%n", e.getMessage());
        } catch (TimeoutException e) {
            System.err.printf("Timeout to publish message to MQTT broker: %s%n", e.getMessage());

        }
    }
}

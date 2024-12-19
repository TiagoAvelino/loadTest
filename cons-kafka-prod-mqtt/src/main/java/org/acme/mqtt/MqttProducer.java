package org.acme.mqtt;

import org.acme.health.IpHealthChecker;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

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

    public void produce(MqttSendMessage mqttMes) {
        tracer = GlobalOpenTelemetry.getTracer("mqtt-kafka", "1.0");

        Span span = tracer.spanBuilder("Producer-Message-Mqtt")
                .setSpanKind(SpanKind.PRODUCER).setAttribute("topic", topic)
                .startSpan();

        System.out.printf("Preparing to send message to topic: %s and host: %s%n", topic,
                MQTT_BROKER_PREFIX + mqttMes.getHost() + ":1883");

        try {
            // Validate IP and health status before proceeding
            // if (!ipHealthChecker.isIpReachableAndHealthy(mqttMes.getHost())) {
            // System.err.printf("IP address %s or its health endpoint is not reachable.
            // Skipping message.%n",
            // mqttMes.getHost());
            // return;
            // }

            MqttClient mqttClient = new MqttClient(MQTT_BROKER_PREFIX + mqttMes.getHost() + ":1883",
                    MqttClient.generateClientId());
            MqttConnectOptions connectOptions = new MqttConnectOptions();
            connectOptions.setConnectionTimeout(10); // Set timeout to 10 seconds
            connectOptions.setKeepAliveInterval(60); // Optional: Set keep-alive interval

            mqttClient.connect();
            System.out.println("Connected to MQTT broker.");

            MqttMessage mqttMessage = new MqttMessage();
            mqttMessage.setPayload(mqttMes.serialize());

            mqttClient.publish(topic, mqttMessage);
            System.out.println("Message published successfully.");

            mqttClient.disconnect();
            System.out.println("Disconnected from MQTT broker.");
        } catch (MqttException e) {
            System.err.printf("Failed to publish message to MQTT broker: %s%n", e.getMessage());
        } finally {
            span.end();
        }
    }
}

package org.acme.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MqttProducer {

    private String MQTT_BROKER = "tcp://";

    private String topic = "";

    public String getTopic() {
        return this.topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    @Inject
    Tracer tracer;

    public void Produce(MqttSendMessage mqttMes) {
        tracer = GlobalOpenTelemetry.getTracer("mqtt-kafka", "1.0");

        Span span = tracer.spanBuilder("Producer-Message-Mqtt")
                .setSpanKind(SpanKind.PRODUCER).setAttribute("topic", topic)
                .startSpan();
        System.out.printf("Enviando mensagens para o topico: %s e host: %s" + topic,
                MQTT_BROKER + mqttMes.getHost() + ":1883");
        System.out.println("Enviando mensagens: " + mqttMes.getMessage());

        try {
            try (Scope scope = span.makeCurrent()) {

                MqttClient mqttClient = new MqttClient(MQTT_BROKER + mqttMes.getHost() + ":1883",
                        MqttClient.generateClientId());
                mqttClient.connect();
                System.out.println("Enviando mensagens: " + mqttMes.getMessage() + "para o topico" + topic);

                MqttMessage mqttMessage = new MqttMessage();
                mqttMessage.setPayload(mqttMes.serialize());

                mqttClient.publish(topic, mqttMessage);

                mqttClient.disconnect();
                mqttClient.close();
            } finally {
                // End the span
                span.end();
            }
        } catch (MqttException e) {
            System.out.println("Failed to publish message to MQTT broker: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

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

    private static final String MQTT_BROKER_PREFIX = "tcp://";

    private String topic = "";

    @Inject
    Tracer tracer;

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
        System.out.printf("Enviando mensagens para o topico: %s e host: %s%n", topic,
                MQTT_BROKER_PREFIX + mqttMes.getHost() + ":1883");
        System.out.println("Enviando mensagens: " + mqttMes.getMessage());

        MqttClient mqttClient = null;
        try {

            mqttClient = new MqttClient(MQTT_BROKER_PREFIX + mqttMes.getHost() + ":1883",
                    MqttClient.generateClientId());
            mqttClient.connect();
            System.out.printf("Enviando mensagens de retorno");

            MqttMessage mqttMessage = new MqttMessage();
            mqttMessage.setPayload(mqttMes.serialize());

            mqttClient.publish(topic, mqttMessage);

        } catch (MqttException e) {
            System.out.println("Failed to publish message to MQTT broker" + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                System.out.println("MQTTCLIENT is connected E MQTT CLIENT CONNECTADO");
                if (mqttClient != null && mqttClient.isConnected()) {
                    System.out.println("MQTTCLIENT NULL E MQTT CLIENT CONNECTADO");

                    mqttClient.disconnect();
                    System.out.println("MQTTCLIENT NULL E MQTT CLIENT CONNECTADO");

                }
                if (mqttClient != null) {
                    System.out.println("MQTTCLIENT NULL");

                    mqttClient.close();
                    System.out.println("MQTTCLIENT NULL");

                }
            } catch (MqttException e) {
                System.out.println("TRATATIVA MQTT TRAVADO");
                e.printStackTrace();
            }
        }

        span.end();
    }
}

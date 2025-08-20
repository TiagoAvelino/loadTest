package org.acme.mqtt;

import java.time.temporal.ChronoUnit;

import org.acme.health.IpHealthChecker;
import org.acme.tracing.TracingBridge;
import org.acme.tracing.messageparams.MqttSendMessage;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MqttProducer {

    private static final String MQTT_BROKER_PREFIX = "tcp://";
    private static final int MQTT_DEFAULT_PORT = 1883;

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

    // The method will timeout if it takes longer than 12 seconds.
    @Timeout(value = 12, unit = ChronoUnit.SECONDS)
    public void produce(MqttSendMessage mqttMes) {
        // fall back if injection hasn’t happened yet for some reason
        if (tracer == null) {
            tracer = GlobalOpenTelemetry.getTracer("mqtt-kafka", "1.0");
        }

        final String brokerHost = mqttMes.getHost();
        final String brokerUrl = MQTT_BROKER_PREFIX + brokerHost + ":" + MQTT_DEFAULT_PORT;

        System.out.printf("Preparing to send message to topic: %s and host: %s%n", topic, brokerUrl);

        // Ensure the payload carries the current W3C context (idempotent).
        // Upstream already injected, but calling again is harmless.
        TracingBridge.injectIntoMessage(mqttMes);

        // Serialize once, reuse for both extraction and publish
        byte[] payloadBytes = mqttMes.serialize();

        // Extract upstream parent from the payload to CONTINUE THE SAME TRACE
        Context parent = TracingBridge.extractFromMessage(payloadBytes);

        // Child span for CONNECT (CLIENT) — no extra PRODUCER span here to avoid
        // duplicates
        Span connectSpan = tracer.spanBuilder("mqtt.connect")
                .setSpanKind(SpanKind.CLIENT)
                .setParent(parent)
                .setAttribute("messaging.system", "mqtt")
                .setAttribute("messaging.url", brokerUrl)
                .setAttribute("messaging.destination_kind", "topic")
                .setAttribute("messaging.destination", topic)
                .startSpan();

        try (Scope cs = connectSpan.makeCurrent()) {
            MqttClient mqttClient = new MqttClient(brokerUrl, MqttClient.generateClientId(), new MemoryPersistence());
            MqttConnectOptions connectOptions = new MqttConnectOptions();
            connectOptions.setConnectionTimeout(10); // seconds
            connectOptions.setKeepAliveInterval(30);
            mqttClient.connect(connectOptions);
            connectSpan.setStatus(StatusCode.OK);

            // Child span for the actual publish I/O
            Span ioSpan = tracer.spanBuilder("mqtt.publish.io")
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute("messaging.system", "mqtt")
                    .setAttribute("messaging.destination_kind", "topic")
                    .setAttribute("messaging.destination", topic)
                    .setAttribute("net.peer.name", brokerHost)
                    .setAttribute("net.peer.port", MQTT_DEFAULT_PORT)
                    .setAttribute("message.payload_size_bytes", payloadBytes != null ? payloadBytes.length : 0)
                    .startSpan();

            try (Scope ios = ioSpan.makeCurrent()) {
                MqttMessage mqttMessage = new MqttMessage();
                mqttMessage.setPayload(payloadBytes);

                mqttClient.publish(topic, mqttMessage);
                ioSpan.setStatus(StatusCode.OK);
            } catch (Exception e) {
                ioSpan.recordException(e);
                ioSpan.setStatus(StatusCode.ERROR, "Publish failed");
                throw e;
            } finally {
                ioSpan.end();
            }

            mqttClient.disconnect();
        } catch (TimeoutException e) {
            connectSpan.recordException(e);
            connectSpan.setStatus(StatusCode.ERROR, "Timeout while publishing to MQTT broker");
            System.err.printf("Timeout to publish message to MQTT broker: %s%n", e.getMessage());
        } catch (MqttException e) {
            connectSpan.recordException(e);
            connectSpan.setStatus(StatusCode.ERROR, "MQTT error");
            System.err.printf("Failed to publish message to MQTT broker: %s %s %s%n",
                    e.getMessage(), e.getCause(), e.getReasonCode());
        } catch (Exception e) {
            connectSpan.recordException(e);
            connectSpan.setStatus(StatusCode.ERROR, "Unexpected error");
            System.err.printf("Unexpected error publishing to MQTT: %s%n", e.getMessage());
        } finally {
            connectSpan.end();
        }
    }
}

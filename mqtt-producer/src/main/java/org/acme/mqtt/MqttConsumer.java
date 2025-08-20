package org.acme.mqtt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;

import org.acme.tracing.TracingBridge;
import org.acme.tracing.messageparams.MqttSendMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.jboss.logging.Logger;

// OpenTelemetry
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
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

    private static final Logger LOGGER = Logger.getLogger(MqttConsumer.class);

    @ConfigProperty(name = "POD_NAME")
    String podName;

    @ConfigProperty(name = "SERVICE")
    String service;

    private IMqttClient client;

    @Inject
    ManagedExecutor managedExecutor;

    // ---- OpenTelemetry ----
    private static final OpenTelemetry OTEL = GlobalOpenTelemetry.get();
    private static final Tracer TRACER = OTEL.getTracer("org.acme.mqtt.consumer", "1.0.0");

    public void onStart(@Observes StartupEvent ev) {
        LOGGER.info("Starting MQTT Consumer Service...");
        String brokerUrl = resolveBrokerUrlFromPodName(podName);
        LOGGER.info("Resolved MQTT broker URL: " + brokerUrl);
        init();
    }

    public void init() {
        try {
            String brokerUrl = resolveBrokerUrlFromPodName(podName);
            LOGGER.info("Resolved MQTT broker URL: " + brokerUrl);

            client = new MqttClient(brokerUrl, MqttClient.generateClientId(), new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);

            client.connect(options);
            LOGGER.info("Connected to MQTT broker for consuming");

            final String topic = "mqtt-message-in/1/2/app/test/push";

            client.subscribe(topic, (t, message) -> {
                long receivedAt = System.nanoTime();
                byte[] payload = message.getPayload();

                // 1) Extract upstream context from payload (best-effort).
                Context extracted = TracingBridge.extractFromMessage(payload);

                // 2) Create a CONSUMER span as the entrypoint for this service.
                Span span = TRACER.spanBuilder("mqtt.receive")
                        .setSpanKind(SpanKind.CONSUMER)
                        .setParent(extracted)
                        .setAttribute("messaging.system", "mqtt")
                        .setAttribute("messaging.operation", "receive")
                        .setAttribute("messaging.destination", topic)
                        .setAttribute("messaging.destination_kind", "topic")
                        .setAttribute("messaging.protocol", "mqtt")
                        .setAttribute("messaging.protocol_version", "3.1.1")
                        .setAttribute("net.peer.name", brokerUrlHost(brokerUrl))
                        .setAttribute("net.peer.port", brokerUrlPort(brokerUrl))
                        .setAttribute("message.payload_size_bytes", payload != null ? payload.length : 0)
                        .startSpan();

                try (Scope s = span.makeCurrent()) {
                    LOGGER.infof("Received message on topic: %s (%d bytes) at %d", t,
                            payload != null ? payload.length : 0, receivedAt);

                    MqttSendMessage receivedMessage = deserialize(payload);
                    if (receivedMessage == null) {
                        span.setStatus(StatusCode.ERROR, "Deserialization returned null");
                        return;
                    }

                    // Optional: add domain attributes you care about for querying later
                    span.setAllAttributes(Attributes.builder()
                            .put("app.pod_name", podName)
                            .put("app.service", service)
                            .put("app.message.type", "MqttSendMessage")
                            .build());

                    // 3) Process the message inside the span scope (child spans encouraged).
                    processMessage(receivedMessage);

                    // 4) Mark success
                    span.setStatus(StatusCode.OK);
                } catch (Exception e) {
                    span.recordException(e);
                    span.setStatus(StatusCode.ERROR, e.getMessage());
                    LOGGER.error("Error processing message from topic: " + t, e);
                } finally {
                    span.end();
                }
            });

        } catch (MqttException e) {
            LOGGER.error("Failed to connect or subscribe to MQTT broker", e);
        }
    }

    private String resolveBrokerUrlFromPodName(String podName) {
        int index = extractOrdinal(podName);
        return "tcp://mqtt-server-" + index + service;
    }

    private int extractOrdinal(String name) {
        try {
            return Integer.parseInt(name.replaceAll(".*-(\\d+)$", "$1"));
        } catch (Exception e) {
            LOGGER.warn("Could not extract pod index from name: " + name + ", defaulting to 0");
            return 0;
        }
    }

    private void processMessage(MqttSendMessage message) {
        // Create a small child span to represent your business logic work.
        Span child = TRACER.spanBuilder("business.process")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        try (Scope s = child.makeCurrent()) {
            LOGGER.info("Message received: " + message.getMessage());
            // ... perform work
            child.setStatus(StatusCode.OK);
        } catch (Exception e) {
            child.recordException(e);
            child.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            child.end();
        }
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

    // --- helpers to annotate peer info ---
    private static String brokerUrlHost(String brokerUrl) {
        try {
            // tcp://host:port
            String u = brokerUrl.replace("tcp://", "");
            int i = u.indexOf(':');
            return i > 0 ? u.substring(0, i) : u;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static long brokerUrlPort(String brokerUrl) {
        try {
            String u = brokerUrl.replace("tcp://", "");
            int i = u.indexOf(':');
            return i > 0 ? Long.parseLong(u.substring(i + 1)) : -1;
        } catch (Exception e) {
            return -1;
        }
    }
}


package org.acme.mqttBroker;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.acme.kafka.KafkaSend;
import org.acme.mqtt.MqttSendMessage;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.jboss.logging.Logger;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@RegisterForReflection
@ApplicationScoped
public class MqttConsumerService {

    private static final Logger LOGGER = Logger.getLogger(MqttConsumerService.class);

    @Inject
    Tracer tracer;
    @Inject
    KafkaSend producer;
    @Inject
    ManagedExecutor managedExecutor;

    private IMqttClient client;

    @Startup(20)
    public void init() {
        Span connectSpan = tracer.spanBuilder("mqtt.connect")
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        try (Scope s = connectSpan.makeCurrent()) {
            connectSpan.setAttribute("messaging.system", "mqtt");
            connectSpan.setAttribute("messaging.url", "tcp://localhost:1883");
            LOGGER.info("Connecting to MQTT broker for consuming");

            client = new MqttClient("tcp://localhost:1883", "consumer-" + InetAddress.getLocalHost().getHostName(),
                    new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            client.connect(options);

            LOGGER.info("Connected to MQTT broker");
            connectSpan.end();

            // subscribe
            Span subSpan = tracer.spanBuilder("mqtt.subscribe")
                    .setSpanKind(SpanKind.CLIENT)
                    .startSpan();
            try (Scope ss = subSpan.makeCurrent()) {
                subSpan.setAttribute("messaging.system", "mqtt");
                subSpan.setAttribute("messaging.destination_kind", "topic");
                subSpan.setAttribute("messaging.destination", "#");

                client.subscribe("#", this::onMessage);
                LOGGER.info("Subscribed to all topics (#)");
            } catch (MqttException e) {
                subSpan.recordException(e);
                subSpan.setStatus(StatusCode.ERROR, "Subscription failed");
                throw e;
            } finally {
                subSpan.end();
            }

        } catch (Exception e) {
            connectSpan.recordException(e);
            connectSpan.setStatus(StatusCode.ERROR, "Connection failed");
            LOGGER.error("Failed to connect or subscribe to MQTT broker", e);
        }
    }

    private void onMessage(String topic, org.eclipse.paho.client.mqttv3.MqttMessage mqttMessage) {
        // wrap processing + send in its own span
        Span processSpan = tracer.spanBuilder("mqtt.process_and_forward")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        try (Scope s = processSpan.makeCurrent()) {
            processSpan.setAttribute("messaging.system", "mqtt");
            processSpan.setAttribute("messaging.destination", topic);
            processSpan.setAttribute("messaging.message_payload_size_bytes", mqttMessage.getPayload().length);

            if (topic.endsWith("/push")) {
                LOGGER.debugf("Skipping push-topic message: %s", topic);
                return;
            }

            // 1) Deserialize
            MqttSendMessage received = deserialize(mqttMessage.getPayload());
            if (received == null) {
                throw new IllegalStateException("Deserialized message was null");
            }

            // 2) Annotate host
            received = getHostIp(received);

            // 3) Business processing
            Span workSpan = tracer.spanBuilder("mqtt.businessLogic")
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
            try (Scope ws = workSpan.makeCurrent()) {
                workSpan.setAttribute("app.stage", "processMessage");
                processMessage(received);
            } catch (Exception e) {
                workSpan.recordException(e);
                workSpan.setStatus(StatusCode.ERROR, "Processing failed");
                throw e;
            } finally {
                workSpan.end();
            }

            // 4) Forward to Kafka
            String key = transformKey(topic);
            String dest = transformTopic(topic);
            Span fwdSpan = tracer.spanBuilder("mqtt.forward_to_kafka")
                    .setSpanKind(SpanKind.PRODUCER)
                    .startSpan();
            try (Scope fs = fwdSpan.makeCurrent()) {
                fwdSpan.setAttribute("messaging.system", "kafka");
                fwdSpan.setAttribute("messaging.destination", dest);
                fwdSpan.setAttribute("messaging.kafka.message_key", key);
                producer.sendMessage(received, key, dest);
            } catch (Exception e) {
                fwdSpan.recordException(e);
                fwdSpan.setStatus(StatusCode.ERROR, "Forwarding failed");
                throw e;
            } finally {
                fwdSpan.end();
            }

        } catch (Exception e) {
            processSpan.recordException(e);
            processSpan.setStatus(StatusCode.ERROR, "Processing or forwarding failed");
            LOGGER.errorf(e, "Error processing message from topic: %s", topic);
        } finally {
            processSpan.end();
        }
    }

    private void processMessage(MqttSendMessage msg) {
        LOGGER.infof("Processing message: %s (host=%s)", msg.getMessage(), msg.getHost());
    }

    private MqttSendMessage getHostIp(MqttSendMessage msg) {
        try {
            String ip = InetAddress.getLocalHost().getHostAddress();
            msg.setHost(ip);
        } catch (UnknownHostException e) {
            LOGGER.warn("Failed to retrieve host IP", e);
        }
        return msg;
    }

    private MqttSendMessage deserialize(byte[] data) {
        try (var in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (MqttSendMessage) in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            Span.current().recordException(e);
            Span.current().setStatus(StatusCode.ERROR, "Deserialization failure");
            LOGGER.error("Failed to deserialize payload", e);
            return null;
        }
    }

    @PreDestroy
    public void cleanup() {
        Span span = tracer.spanBuilder("mqtt.disconnect").startSpan();
        try (Scope s = span.makeCurrent()) {
            if (client != null && client.isConnected()) {
                client.disconnect();
                client.close();
                LOGGER.info("Disconnected from MQTT broker");
            }
        } catch (MqttException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, "Disconnect failed");
            LOGGER.error("Failed to disconnect from MQTT broker", e);
        } finally {
            span.end();
        }
    }

    public static String transformKey(String mqttTopic) {
        if (mqttTopic == null || mqttTopic.split("/").length < 4) {
            throw new IllegalArgumentException("Invalid input format: The string must have at least 3 '/' characters.");
        }

        // Split the topic into parts
        String[] parts = mqttTopic.split("/", 4); // At most 4 parts

        // Join the first three parts and replace "/" with "."
        String beforeThirdSlash = String.join("/", parts[0], parts[1], parts[2]);
        return beforeThirdSlash.replace("/", ".");
    }

    public static String transformTopic(String mqttTopic) {
        if (mqttTopic == null || mqttTopic.split("/").length < 4) {
            throw new IllegalArgumentException("Invalid input format: The string must have at least 3 '/' characters.");
        }

        // Split the topic into parts
        String[] parts = mqttTopic.split("/", 4); // At most 4 parts

        // Get the part after the third slash and replace "/" with "."
        String afterThirdSlash = parts[3];
        return afterThirdSlash.replace("/", ".");
    }
}
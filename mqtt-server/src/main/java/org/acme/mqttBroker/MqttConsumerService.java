package org.acme.mqttBroker;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.acme.kafka.KafkaSend;
import org.acme.tracing.TracingBridge; // <-- shared lib used to extract/inject trace context
import org.acme.tracing.messageparams.MqttSendMessage;
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
import io.opentelemetry.context.Context;
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
            connectSpan.setStatus(StatusCode.OK);
        } catch (Exception e) {
            connectSpan.recordException(e);
            connectSpan.setStatus(StatusCode.ERROR, "Connection failed");
            LOGGER.error("Failed to connect to MQTT broker", e);
        } finally {
            connectSpan.end();
        }

        // subscribe (outside the connect try so errors here are captured separately)
        Span subSpan = tracer.spanBuilder("mqtt.subscribe")
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        try (Scope ss = subSpan.makeCurrent()) {
            subSpan.setAttribute("messaging.system", "mqtt");
            subSpan.setAttribute("messaging.destination_kind", "topic");
            subSpan.setAttribute("messaging.destination", "#");

            client.subscribe("#", this::onMessage);
            LOGGER.info("Subscribed to all topics (#)");
            subSpan.setStatus(StatusCode.OK);
        } catch (MqttException e) {
            subSpan.recordException(e);
            subSpan.setStatus(StatusCode.ERROR, "Subscription failed");
            LOGGER.error("Failed to subscribe to MQTT broker", e);
        } finally {
            subSpan.end();
        }
    }

    private void onMessage(String topic, org.eclipse.paho.client.mqttv3.MqttMessage mqttMessage) {
        // 0) Extract upstream context from the MQTT payload so THIS SERVICE continues
        byte[] payload = mqttMessage.getPayload();
        Context extractedParent = TracingBridge.extractFromMessage(payload);

        // 1) Create a CONSUMER span *with the extracted parent* (keeps everything in
        // one trace)
        Span receiveSpan = tracer.spanBuilder("mqtt.receive")
                .setSpanKind(SpanKind.CONSUMER)
                .setParent(extractedParent)
                .setAttribute("messaging.system", "mqtt")
                .setAttribute("messaging.operation", "receive")
                .setAttribute("messaging.destination_kind", "topic")
                .setAttribute("messaging.destination", topic)
                .setAttribute("message.payload_size_bytes", payload != null ? payload.length : 0)
                .startSpan();

        try (Scope rs = receiveSpan.makeCurrent()) {
            if (topic != null && topic.endsWith("/push")) {
                LOGGER.debugf("Skipping push-topic message: %s", topic);
                receiveSpan.setStatus(StatusCode.OK);
                return;
            }

            // 2) Deserialize the domain message (still under the receive span)
            MqttSendMessage received = deserialize(payload);
            if (received == null) {
                throw new IllegalStateException("Deserialized message was null");
            }

            // (Optional) annotate this host/IP in the domain object
            received = attachHostIp(received);

            // 3) Business processing as a child INTERNAL span
            Span workSpan = tracer.spanBuilder("mqtt.businessLogic")
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
            try (Scope ws = workSpan.makeCurrent()) {
                workSpan.setAttribute("app.stage", "processMessage");
                processMessage(received);
                workSpan.setStatus(StatusCode.OK);
            } catch (Exception e) {
                workSpan.recordException(e);
                workSpan.setStatus(StatusCode.ERROR, "Processing failed");
                throw e;
            } finally {
                workSpan.end();
            }

            // 4) Re-inject context into the message before forwarding (keeps the same trace
            // downstream)
            TracingBridge.injectIntoMessage(received);

            // 5) Forward to Kafka with a PRODUCER span (child of the receive span)
            String key = transformKey(topic);
            String dest = transformTopic(topic);

            Span fwdSpan = tracer.spanBuilder("mqtt.forward_to_kafka")
                    .setSpanKind(SpanKind.PRODUCER)
                    .setAttribute("messaging.system", "kafka")
                    .setAttribute("messaging.destination_kind", "topic")
                    .setAttribute("messaging.destination", dest)
                    .setAttribute("messaging.kafka.message_key", key)
                    .startSpan();
            try (Scope fs = fwdSpan.makeCurrent()) {
                // If your KafkaSend supports headers, you can also inject into headers there.
                // Here we at least ensured the payload carries traceparent/tracestate forward.
                producer.sendMessage(received, key, dest);
                fwdSpan.setStatus(StatusCode.OK);
            } catch (Exception e) {
                fwdSpan.recordException(e);
                fwdSpan.setStatus(StatusCode.ERROR, "Forwarding failed");
                throw e;
            } finally {
                fwdSpan.end();
            }

            receiveSpan.setStatus(StatusCode.OK);
        } catch (Exception e) {
            receiveSpan.recordException(e);
            receiveSpan.setStatus(StatusCode.ERROR, "Processing or forwarding failed");
            LOGGER.errorf(e, "Error processing message from topic: %s", topic);
        } finally {
            receiveSpan.end();
        }
    }

    private void processMessage(MqttSendMessage msg) {
        LOGGER.infof("Processing message: %s (host=%s)", msg.getMessage(), msg.getHost());
    }

    private MqttSendMessage attachHostIp(MqttSendMessage msg) {
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
        Span span = tracer.spanBuilder("mqtt.disconnect")
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        try (Scope s = span.makeCurrent()) {
            if (client != null && client.isConnected()) {
                client.disconnect();
                client.close();
                LOGGER.info("Disconnected from MQTT broker");
            }
            span.setStatus(StatusCode.OK);
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
        String[] parts = mqttTopic.split("/", 4);
        String beforeThirdSlash = String.join("/", parts[0], parts[1], parts[2]);
        return beforeThirdSlash.replace("/", ".");
    }

    public static String transformTopic(String mqttTopic) {
        if (mqttTopic == null || mqttTopic.split("/").length < 4) {
            throw new IllegalArgumentException("Invalid input format: The string must have at least 3 '/' characters.");
        }
        String[] parts = mqttTopic.split("/", 4);
        String afterThirdSlash = parts[3];
        return afterThirdSlash.replace("/", ".");
    }
}

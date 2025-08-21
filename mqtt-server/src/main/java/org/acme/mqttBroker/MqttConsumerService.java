package org.acme.mqttBroker;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.acme.kafka.KafkaSend;
import org.acme.tracing.TracingBridge;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
    private volatile String localHostIp = "unknown";

    // ---- Tunables (env-friendly) ------------------------------------------------
    @ConfigProperty(name = "mqtt.url", defaultValue = "tcp://localhost:1883")
    String mqttUrl;

    @ConfigProperty(name = "mqtt.topic.filter", defaultValue = "mqtt-message-in/+/+/app/test/pull")
    String topicFilter;

    @ConfigProperty(name = "mqtt.subscribe.qos", defaultValue = "0")
    int subscribeQos;

    @ConfigProperty(name = "mqtt.cleanSession", defaultValue = "false")
    boolean cleanSession;

    @ConfigProperty(name = "mqtt.maxInflight", defaultValue = "1024")
    int maxInflight;

    // Replace outgoing Kafka topic suffix ".pull" -> ".push"
    @ConfigProperty(name = "mqtt.forward.replacePullWithPush", defaultValue = "true")
    boolean replacePullWithPush;
    // -----------------------------------------------------------------------------

    @Startup(20)
    public void init() {
        try {
            localHostIp = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            LOGGER.warn("Failed to resolve host IP at startup", e);
        }

        Span connectSpan = tracer.spanBuilder("mqtt.connect").setSpanKind(SpanKind.CLIENT).startSpan();
        try (Scope s = connectSpan.makeCurrent()) {
            connectSpan.setAttribute("messaging.system", "mqtt");
            connectSpan.setAttribute("messaging.url", mqttUrl);
            LOGGER.infof("Connecting MQTT consumer to %s (filter=%s, qos=%d)", mqttUrl, topicFilter, subscribeQos);

            client = new MqttClient(mqttUrl, "consumer-" + InetAddress.getLocalHost().getHostName(),
                    new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(cleanSession);
            options.setMaxInflight(maxInflight);
            options.setKeepAliveInterval(30);
            options.setConnectionTimeout(5);

            client.connect(options);
            connectSpan.setStatus(StatusCode.OK);
        } catch (Exception e) {
            connectSpan.recordException(e);
            connectSpan.setStatus(StatusCode.ERROR, "Connection failed");
            LOGGER.error("Failed to connect to MQTT broker", e);
            return;
        } finally {
            connectSpan.end();
        }

        Span subSpan = tracer.spanBuilder("mqtt.subscribe").setSpanKind(SpanKind.CLIENT).startSpan();
        try (Scope ss = subSpan.makeCurrent()) {
            subSpan.setAttribute("messaging.system", "mqtt");
            subSpan.setAttribute("messaging.destination_kind", "topic");
            subSpan.setAttribute("messaging.destination", topicFilter);

            client.subscribe(topicFilter, subscribeQos, this::onMessage);
            LOGGER.infof("Subscribed to '%s' with QoS=%d", topicFilter, subscribeQos);
            subSpan.setStatus(StatusCode.OK);
        } catch (MqttException e) {
            subSpan.recordException(e);
            subSpan.setStatus(StatusCode.ERROR, "Subscription failed");
            LOGGER.error("Failed to subscribe to MQTT broker", e);
        } finally {
            subSpan.end();
        }
    }

    // Keep callback thin; do real work on the executor
    private void onMessage(String topic, org.eclipse.paho.client.mqttv3.MqttMessage mqttMessage) {
        final byte[] payload = mqttMessage.getPayload();
        managedExecutor.execute(() -> handleMessage(topic, payload));
    }

    private void handleMessage(String topic, byte[] payload) {
        Context extractedParent = TracingBridge.extractFromMessage(payload);

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
                receiveSpan.setStatus(StatusCode.OK);
                return;
            }

            MqttSendMessage received = deserialize(payload);
            if (received == null)
                throw new IllegalStateException("Deserialized message was null");

            // cache IP set
            received.setHost(localHostIp);

            // Business work
            Span workSpan = tracer.spanBuilder("mqtt.businessLogic").setSpanKind(SpanKind.INTERNAL).startSpan();
            try (Scope ws = workSpan.makeCurrent()) {
                processMessage(received);
                workSpan.setStatus(StatusCode.OK);
            } catch (Exception e) {
                workSpan.recordException(e);
                workSpan.setStatus(StatusCode.ERROR, "Processing failed");
                throw e;
            } finally {
                workSpan.end();
            }

            // re-inject trace context
            TracingBridge.injectIntoMessage(received);

            // Transform destination + key
            String key = transformKey(topic);
            String dest = transformTopic(topic);
            if (replacePullWithPush && dest.endsWith(".pull")) {
                dest = dest.substring(0, dest.length() - 5) + ".push";
            }

            Span fwdSpan = tracer.spanBuilder("mqtt.forward_to_kafka")
                    .setSpanKind(SpanKind.PRODUCER)
                    .setAttribute("messaging.system", "kafka")
                    .setAttribute("messaging.destination_kind", "topic")
                    .setAttribute("messaging.destination", dest)
                    .setAttribute("messaging.kafka.message_key", key)
                    .startSpan();

            try (Scope fs = fwdSpan.makeCurrent()) {
                // Send directly (we're already off the callback thread). No join/flush here.
                LOGGER.debugf("Forwarding to Kafka topic='%s' key='%s'", dest, key);
                producer.sendMessage(received, key, dest);
                fwdSpan.setStatus(StatusCode.OK);
            } catch (Exception e) {
                fwdSpan.recordException(e);
                fwdSpan.setStatus(StatusCode.ERROR, "Forwarding failed");
                LOGGER.errorf(e, "Kafka send failed (topic=%s, key=%s)", dest, key);
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
        LOGGER.debugf("Processing message: %s (host=%s)", msg.getMessage(), msg.getHost());
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
        Span span = tracer.spanBuilder("mqtt.disconnect").setSpanKind(SpanKind.CLIENT).startSpan();
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

    // Faster split-less transforms
    public static String transformKey(String mqttTopic) {
        int first = mqttTopic.indexOf('/');
        if (first < 0)
            throw new IllegalArgumentException("Invalid MQTT topic: " + mqttTopic);
        int second = mqttTopic.indexOf('/', first + 1);
        if (second < 0)
            throw new IllegalArgumentException("Invalid MQTT topic: " + mqttTopic);
        int third = mqttTopic.indexOf('/', second + 1);
        if (third < 0)
            throw new IllegalArgumentException("Invalid MQTT topic: " + mqttTopic);
        return mqttTopic.substring(0, third).replace('/', '.'); // e.g., mqtt-message-in.1.2
    }

    public static String transformTopic(String mqttTopic) {
        int first = mqttTopic.indexOf('/');
        if (first < 0)
            throw new IllegalArgumentException("Invalid MQTT topic: " + mqttTopic);
        int second = mqttTopic.indexOf('/', first + 1);
        if (second < 0)
            throw new IllegalArgumentException("Invalid MQTT topic: " + mqttTopic);
        int third = mqttTopic.indexOf('/', second + 1);
        if (third < 0)
            throw new IllegalArgumentException("Invalid MQTT topic: " + mqttTopic);
        return mqttTopic.substring(third + 1).replace('/', '.'); // e.g., app.test.pull
    }
}

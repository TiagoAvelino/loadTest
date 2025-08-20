package org.acme.mqtt;

import org.acme.tracing.TracingBridge;
import org.acme.tracing.messageparams.MqttSendMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
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
import io.opentelemetry.context.Scope;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MqttClientService {

    private IMqttClient client;

    @ConfigProperty(name = "POD_NAME")
    String podName;

    @ConfigProperty(name = "SERVICE")
    String service;

    private String broker;

    private volatile boolean connecting;
    private static final Logger logger = Logger.getLogger(MqttClientService.class);

    // ---- OpenTelemetry ----
    private static final OpenTelemetry OTEL = GlobalOpenTelemetry.get();
    private static final Tracer TRACER = OTEL.getTracer("org.acme.mqtt.publisher", "1.0.0");

    @PostConstruct
    void configureBroker() {
        int index = extractOrdinal(podName);
        logger.info("Resolved index: " + index);

        broker = "tcp://mqtt-server-" + index + service;
        logger.info("Resolved broker address: " + broker);
    }

    public void init(String topic) {
        connectAndSubscribe(topic);
    }

    private synchronized void connectAndSubscribe(String topic) {
        if (client == null || !client.isConnected()) {
            try {
                logger.info("Connecting to: " + broker + " Mqtt Server");
                client = new MqttClient(broker, MqttClient.generateClientId(), new MemoryPersistence());
                MqttConnectOptions connOpts = new MqttConnectOptions();
                connOpts.setCleanSession(true);
                connOpts.setMaxInflight(1000);

                connecting = true;
                IMqttToken token = client.connectWithResult(connOpts);
                token.waitForCompletion();
                connecting = false;

                logger.info("Connected to the: " + broker + " Mqtt Server");

                client.setCallback(getCallback(topic));
                logger.info("Subscribing to topic: " + topic);
                client.subscribe(topic, 0);
                logger.info("Successfully subscribed to topic: " + topic);
            } catch (MqttException me) {
                connecting = false;
                logger.error("Error while connecting or subscribing to MQTT broker: ", me);
            }
        }
    }

    public void publishMessage(String topic, MqttSendMessage payload) {
        // Create a PRODUCER span to represent this publish operation.
        Span span = TRACER.spanBuilder("mqtt.publish")
                .setSpanKind(SpanKind.PRODUCER)
                .setAttribute("messaging.system", "mqtt")
                .setAttribute("messaging.operation", "publish")
                .setAttribute("messaging.destination", topic)
                .setAttribute("messaging.destination_kind", "topic")
                .setAttribute("messaging.protocol", "mqtt")
                .setAttribute("messaging.protocol_version", "3.1.1")
                .setAttribute("net.peer.name", brokerUrlHost(broker))
                .setAttribute("net.peer.port", brokerUrlPort(broker))
                .startSpan();

        try (Scope s = span.makeCurrent()) {
            ensureConnected(topic);

            // Inject context so downstream services can continue the trace.
            TracingBridge.injectIntoMessage(payload);

            byte[] data = payload.serialize();
            logger.infof("Publishing message. Size: %d bytes", data.length);

            // (Optional) annotate payload size
            span.setAllAttributes(Attributes.of(
                    io.opentelemetry.api.common.AttributeKey.longKey("message.payload_size_bytes"), (long) data.length,
                    io.opentelemetry.api.common.AttributeKey.stringKey("app.pod_name"), podName,
                    io.opentelemetry.api.common.AttributeKey.stringKey("app.service"), service));

            MqttMessage message = new MqttMessage(data);
            message.setQos(0); // test with 0 for throughput; bump to 1/2 if you need delivery guarantees

            long start = System.nanoTime();
            client.publish(topic, message);
            long end = System.nanoTime();

            span.setStatus(StatusCode.OK);
            span.setAttribute("message.publish_latency_ms", (end - start) / 1_000_000.0);
            logger.infof("Published in %.2f ms", (end - start) / 1_000_000.0);
        } catch (MqttException | InterruptedException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            logger.error("Error while publishing message to MQTT broker: ", e);
        } finally {
            span.end();
        }
    }

    private synchronized void ensureConnected(String topic) throws InterruptedException {
        if (client == null || !client.isConnected()) {
            connectAndSubscribe(topic);
        }

        int retries = 0;
        while (connecting && retries < 50) {
            Thread.sleep(10);
            retries++;
        }

        if (!client.isConnected()) {
            throw new IllegalStateException("Failed to connect to MQTT broker after retries");
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (client != null) {
                client.disconnect();
                client.close();
                logger.info("Disconnected from MQTT broker");
            }
        } catch (MqttException e) {
            logger.error("Error while disconnecting MQTT client", e);
        }
    }

    private MqttCallback getCallback(String topic) {
        return new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                logger.warn("Connection lost: " + cause.getMessage());
                connectAndSubscribe(topic);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                long receivedAt = System.nanoTime();
                logger.info("Message arrived. Topic: " + topic + " Size: " + message.getPayload().length + " bytes at "
                        + receivedAt);
                // If this service also consumes its own publishes, you could extract and create
                // a span here.
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                logger.debug("Delivery complete for token: " + token.getMessageId());
            }
        };
    }

    private int extractOrdinal(String podName) {
        try {
            return Integer.parseInt(podName.replaceAll(".*-(\\d+)$", "$1"));
        } catch (Exception e) {
            logger.warn("Could not extract ordinal from pod name '" + podName + "', defaulting to 0");
            return 0;
        }
    }

    private static String brokerUrlHost(String brokerUrl) {
        try {
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

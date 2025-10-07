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
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

@ApplicationScoped
public class MqttProducer {

    private static final Logger LOGGER = Logger.getLogger(MqttProducer.class);

    private IMqttClient client;

    @ConfigProperty(name = "mqtt.url", defaultValue = "tcp://mqtt-server-0.mqtt-server-headless.kafka.svc.cluster.local:1883")
    String broker;

    private volatile boolean connecting;

    // Topic is supplied by the previous step (Kafka consumer sets it)
    private volatile String topic = "";

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    @Inject
    ObjectMapper objectMapper;
    private ObjectWriter mqttMessageWriter;

    @Inject
    Tracer tracer;

    @PostConstruct
    void init() {
        mqttMessageWriter = objectMapper.writerFor(MqttSendMessage.class);
        LOGGER.infof("MQTT producer will use broker: %s", broker);
    }

    public void produce(MqttSendMessage payload) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalStateException("setTopic(...) before produce()");
        }

        // Capture parent explicitly to be robust to any thread hop
        Context parent = Context.current();

        Span sendSpan = tracer.spanBuilder("mqtt.send")
                .setSpanKind(SpanKind.PRODUCER)
                .setParent(parent) // <— IMPORTANT: behave like your old Outgoing.parentCtx
                .setAttribute("messaging.system", "mqtt")
                .setAttribute("messaging.destination_kind", "topic")
                .setAttribute("messaging.destination", topic)
                .setAttribute("net.peer.name", broker)
                .startSpan();

        try (Scope scope = sendSpan.makeCurrent()) {

            try {
                ensureConnected(topic);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendSpan.recordException(e);
                sendSpan.setStatus(StatusCode.ERROR, "Interrupted while connecting to MQTT broker");
                throw new IllegalStateException("Interrupted while connecting to MQTT broker", e);
            } catch (RuntimeException re) {
                sendSpan.recordException(re);
                sendSpan.setStatus(StatusCode.ERROR, "Failed to connect to MQTT broker");
                throw re;
            }

            // Inject tracecontext into payload while span is current
            try {
                TracingBridge.injectIntoMessage(payload);
                if (LOGGER.isDebugEnabled()) {
                    // Quick breadcrumb to ensure fields are present in the object before serialize
                    LOGGER.debugf("traceparent after inject: %s", payload.getTraceParent());
                }
            } catch (Throwable t) {
                LOGGER.warn("Tracing injection failed; publishing without enriched context", t);
                sendSpan.recordException(t);
            }

            byte[] data;
            try {
                data = mqttMessageWriter.writeValueAsBytes(payload);
                sendSpan.setAttribute("messaging.message_payload_size_bytes", data.length);
                if (LOGGER.isDebugEnabled()) {
                    // Shallow peek to confirm trace keys made it into JSON bytes
                    String head = new String(data, 0, Math.min(200, data.length));
                    LOGGER.debugf("payload head: %s", head);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to serialize message to JSON", e);
                sendSpan.recordException(e);
                sendSpan.setStatus(StatusCode.ERROR, "serialization error");
                return;
            }

            MqttMessage message = new MqttMessage(data);
            message.setQos(0);
            sendSpan.setAttribute("messaging.mqtt.qos", 0);

            long start = System.nanoTime();
            try {
                // Synchronous publish (ok); if you switch back to async, end span in the
                // callback
                client.publish(topic, message);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                sendSpan.setAttribute("messaging.mqtt.publish_ms", elapsedMs);
                sendSpan.setStatus(StatusCode.OK);
                LOGGER.infof("Published to %s in %d ms", topic, elapsedMs);
            } catch (MqttPersistenceException e) {
                LOGGER.error("MQTT persistence error while publishing", e);
                sendSpan.recordException(e);
                sendSpan.setStatus(StatusCode.ERROR, "mqtt persistence error");
            } catch (MqttException e) {
                LOGGER.error("MQTT error while publishing", e);
                sendSpan.recordException(e);
                sendSpan.setStatus(StatusCode.ERROR, "mqtt publish error");
            }

        } finally {
            sendSpan.end();
        }
    }

    private synchronized void ensureConnected(String publishTopic) throws InterruptedException {
        if (client == null || !client.isConnected()) {
            connect(publishTopic);
        }
        int retries = 0;
        while (connecting && retries < 50) {
            Thread.sleep(10);
            retries++;
        }
        if (client == null || !client.isConnected()) {
            throw new IllegalStateException("Failed to connect to MQTT broker after retries");
        }
    }

    private void connect(String subscribeEchoTopicIfAny) {
        if (client != null && client.isConnected())
            return;

        try {
            LOGGER.infof("Connecting to MQTT broker: %s", broker);
            client = new MqttClient(broker, MqttClient.generateClientId(), new MemoryPersistence());

            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);
            connOpts.setMaxInflight(1000);

            connecting = true;
            IMqttToken token = client.connectWithResult(connOpts);
            token.waitForCompletion();
            connecting = false;

            LOGGER.infof("Connected to MQTT broker: %s", broker);
            client.setCallback(getCallback());
        } catch (MqttException me) {
            connecting = false;
            LOGGER.error("Error while connecting to MQTT broker: ", me);
            throw new IllegalStateException("MQTT connect error", me);
        }
    }

    private MqttCallback getCallback() {
        return new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                LOGGER.warn("MQTT connection lost: " + (cause != null ? cause.getMessage() : "unknown"));
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debugf("Unexpected message on topic=%s len=%d", topic, message.getPayload().length);
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debugf("Delivery complete for token: %s", token != null ? token.getMessageId() : "null");
                }
            }
        };
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
            LOGGER.error("Error while disconnecting MQTT client", e);
        }
    }
}

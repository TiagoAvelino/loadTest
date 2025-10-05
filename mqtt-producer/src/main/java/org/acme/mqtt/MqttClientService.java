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

    // Jackson: use a prebuilt writer for speed and thread-safety
    @Inject
    ObjectMapper objectMapper;
    private ObjectWriter mqttMessageWriter;

    @PostConstruct
    void configureBroker() {
        int index = extractOrdinal(podName);
        logger.info("Resolved index: " + index);

        broker = "tcp://mqtt-server-" + index + service;
        logger.info("Resolved broker address: " + broker);

        // Build once to avoid per-call reconfiguration
        mqttMessageWriter = objectMapper.writerFor(MqttSendMessage.class);
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
                connOpts.setMaxInflight(1000); // QoS0: keep modest to reduce memory pressure

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
        try {
            ensureConnected(topic);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while connecting to MQTT broker", e);
        }

        byte[] data;
        try {
            // Serialize to JSON bytes
            data = mqttMessageWriter.writeValueAsBytes(payload);
        } catch (Exception e) {
            logger.error("Failed to serialize message to JSON", e);
            return;
        }

        if (logger.isDebugEnabled()) {
            logger.debugf("Publishing message. Size: %d bytes", data.length);
        }

        MqttMessage message = new MqttMessage(data);
        message.setQos(0); // throughput-optimized; increase to 1/2 only if you need delivery guarantees

        long start = System.nanoTime();
        try {
            client.publish(topic, message);
        } catch (MqttPersistenceException e) {
            logger.error("MQTT persistence error while publishing", e);
        } catch (MqttException e) {
            logger.error("MQTT error while publishing", e);
        }
        long end = System.nanoTime();
        logger.infof("Published in %.2f ms", (end - start) / 1_000_000.0);
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
                if (logger.isDebugEnabled()) {
                    logger.debug("Message arrived. Topic: " + topic + " Size: " + message.getPayload().length
                            + " bytes at " + receivedAt);
                }
                // If this service also consumes its own publishes, you could extract and create
                // a span here.
                // Example (mirrors your consumer):
                // Context ctx = TracingBridge.extractFromMessage(message.getPayload());
                // Span span = TracingBridge.mqttConsumerSpan(...).startSpan(); ...
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Delivery complete for token: " + token.getMessageId());
                }
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

    @SuppressWarnings("unused")
    private static String brokerUrlHost(String brokerUrl) {
        try {
            String u = brokerUrl.replace("tcp://", "");
            int i = u.indexOf(':');
            return i > 0 ? u.substring(0, i) : u;
        } catch (Exception e) {
            return "unknown";
        }
    }

    @SuppressWarnings("unused")
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

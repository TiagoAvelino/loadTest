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
import org.jboss.logging.Logger;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
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

    private IMqttClient client;

    @Inject
    ManagedExecutor managedExecutor;

    public void init() {
        tracer = GlobalOpenTelemetry.getTracer("cons-mqtt-produce-kafka", "1.0");
        Span initSpan = tracer.spanBuilder("Consumer-Mqtt-Init").startSpan();
        try (Scope scope = initSpan.makeCurrent()) {
            client = new MqttClient("tcp://localhost:1883", "1");
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            client.connect(options);
            LOGGER.info("Connected to MQTT broker for consuming");
            client.subscribe("#", (topic, message) -> {
                Span messageSpan = tracer.spanBuilder("Consume-Message")
                        .startSpan();
                try (Scope messageScope = messageSpan.makeCurrent()) {
                    LOGGER.info("Recebido no topico:" + topic);

                    if (topic.endsWith("/push")) {
                        LOGGER.info("Recebido no topico push");
                        LOGGER.info(topic);

                    } else {
                        MqttSendMessage receivedMessage = deserialize(message.getPayload());
                        receivedMessage = getHostIp(receivedMessage);
                        processMessage(receivedMessage);

                        KafkaSend producer = new KafkaSend();
                        producer.sendMessage(receivedMessage, transformKey(topic), transformTopic(topic));

                    }

                } finally {
                    messageSpan.end();
                }
            });
        } catch (MqttException e) {
            LOGGER.error("Failed to connect to MQTT broker for consuming", e);
        } finally {
            initSpan.end();
        }
    }

    private void processMessage(MqttSendMessage message) {
        // Add logic to process the received message
        LOGGER.info("Processing message in firs-consumer: " + message.getMessage());
        LOGGER.info("Processing message in firs-consumer: " + message.getHost());

    }

    private MqttSendMessage getHostIp(MqttSendMessage message) {
        try {
            LOGGER.info("GetHostIp:");
            InetAddress inetAddress = InetAddress.getLocalHost();
            message.setHost(inetAddress.getHostAddress());
            return message;
        } catch (UnknownHostException e) {
            return message;
        }
    }

    // void onStart(@Observes StartupEvent ev) {
    // System.out.println("Connecting to MQTT broker: ");

    // init();
    // }

    private MqttSendMessage deserialize(byte[] data) {
        try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
                ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream)) {
            return (MqttSendMessage) objectInputStream.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            return null; // Handle error gracefully
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

    public String transformTopic(String topic) {
        // Split the topic string by '/'
        String[] parts = topic.split("/");

        // Join the last two parts with a dot
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    public String transformKey(String topic) {
        // Find the index of the third occurrence of "/"
        int thirdSlashIndex = topic.indexOf('/', topic.indexOf('/') + 1);
        thirdSlashIndex = topic.indexOf('/', thirdSlashIndex + 1);

        // Extract the substring up to the third occurrence of "/"
        String firstThreeParts = topic.substring(0, thirdSlashIndex);

        // Replace remaining "/" with "."
        return firstThreeParts.replace('/', '.');
    }
}

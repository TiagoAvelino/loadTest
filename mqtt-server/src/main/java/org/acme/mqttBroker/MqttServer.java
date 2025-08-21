package org.acme.mqttBroker;

import java.io.IOException;
import java.util.Properties;

import org.jboss.logging.Logger;

import io.moquette.BrokerConstants;
import io.moquette.broker.Server;
import io.moquette.broker.config.MemoryConfig;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class MqttServer {

    private static final Logger LOGGER = Logger.getLogger(MqttServer.class);

    private Server mqttBroker;

    // Make ports tunable via env (e.g., MQTT_BROKER_PORT, MQTT_BROKER_WS_PORT)
    @ConfigProperty(name = "mqtt.broker.port", defaultValue = "1883")
    int mqttPort;

    @ConfigProperty(name = "mqtt.broker.ws.port", defaultValue = "8090")
    int wsPort;

    // Allow anonymous for pure internal traffic (turn off if you need auth)
    @ConfigProperty(name = "mqtt.broker.allowAnonymous", defaultValue = "true")
    boolean allowAnonymous;

    // Keep everything in-memory for lowest latency
    @ConfigProperty(name = "mqtt.broker.usePersistentStore", defaultValue = "false")
    boolean usePersistentStore;

    @Startup(10)
    public void start() {
        LOGGER.info("Initializing MQTT Broker...");
        if (mqttBroker != null) {
            LOGGER.info("MQTT Broker already started");
            return;
        }

        mqttBroker = new Server();
        Properties props = new Properties();

        // TCP
        props.setProperty(BrokerConstants.HOST_PROPERTY_NAME, "0.0.0.0");
        props.setProperty(BrokerConstants.PORT_PROPERTY_NAME, Integer.toString(mqttPort));

        // WebSocket
        props.setProperty(BrokerConstants.WEB_SOCKET_PORT_PROPERTY_NAME, Integer.toString(wsPort));
        props.setProperty(BrokerConstants.WEB_SOCKET_PATH_PROPERTY_NAME, BrokerConstants.WEBSOCKET_PATH);

        // Auth
        props.setProperty(BrokerConstants.ALLOW_ANONYMOUS_PROPERTY_NAME, Boolean.toString(allowAnonymous));

        // Persistence: fastest is memory; enable MapDB only if you need durable
        // sessions
        if (usePersistentStore) {
            // Example: props.setProperty(BrokerConstants.PERSISTENT_STORE_PROPERTY_NAME,
            // "mqtt_store.mapdb");
        } else {
            // Explicitly force memory to avoid unintended disk overhead
            props.setProperty(BrokerConstants.PERSISTENT_STORE_PROPERTY_NAME, "");
        }

        try {
            mqttBroker.startServer(new MemoryConfig(props));
            LOGGER.infof("MQTT Broker started on tcp/%d and ws/%d", mqttPort, wsPort);
        } catch (IOException e) {
            LOGGER.error("Failed to start MQTT Broker", e);
        }
    }

    @PreDestroy
    public void stop() {
        LOGGER.info("Stopping MQTT Broker...");
        if (mqttBroker != null) {
            mqttBroker.stopServer();
            LOGGER.info("MQTT Broker stopped");
        }
    }
}

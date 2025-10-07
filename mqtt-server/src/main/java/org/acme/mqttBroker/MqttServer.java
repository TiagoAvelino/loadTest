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

    // Listener config (tunable via env)
    @ConfigProperty(name = "mqtt.broker.host", defaultValue = "0.0.0.0")
    String host;

    @ConfigProperty(name = "mqtt.broker.port", defaultValue = "1883")
    int mqttPort;

    // Toggle WebSocket listener entirely (disable if unused for lower latency)
    @ConfigProperty(name = "mqtt.broker.ws.enabled", defaultValue = "false")
    boolean wsEnabled;

    @ConfigProperty(name = "mqtt.broker.ws.port", defaultValue = "8090")
    int wsPort;

    // Auth
    @ConfigProperty(name = "mqtt.broker.allowAnonymous", defaultValue = "true")
    boolean allowAnonymous;

    // Pure memory (fastest). If you really need durable sessions, flip to true and
    // configure a store file.
    @ConfigProperty(name = "mqtt.broker.usePersistentStore", defaultValue = "false")
    boolean usePersistentStore;

    @Startup(10)
    public void start() {
        if (mqttBroker != null) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("MQTT Broker already started");
            return;
        }

        mqttBroker = new Server();
        Properties props = new Properties();

        // --- TCP listener ---
        props.setProperty(BrokerConstants.HOST_PROPERTY_NAME, host);
        props.setProperty(BrokerConstants.PORT_PROPERTY_NAME, Integer.toString(mqttPort));

        // --- WebSocket listener (optional) ---
        if (wsEnabled) {
            props.setProperty(BrokerConstants.WEB_SOCKET_PORT_PROPERTY_NAME, Integer.toString(wsPort));
            props.setProperty(BrokerConstants.WEB_SOCKET_PATH_PROPERTY_NAME, BrokerConstants.WEBSOCKET_PATH);
        } else {
            // Ensure no WS listener is created
            props.setProperty(BrokerConstants.WEB_SOCKET_PORT_PROPERTY_NAME, "0");
        }

        // --- Auth ---
        props.setProperty(BrokerConstants.ALLOW_ANONYMOUS_PROPERTY_NAME, Boolean.toString(allowAnonymous));

        // --- Persistence ---
        if (usePersistentStore) {
            // props.setProperty(BrokerConstants.PERSISTENT_STORE_PROPERTY_NAME,
            // "mqtt_store.mapdb");
        } else {
            // Empty value means no persistent store => keep everything in memory
            props.setProperty(BrokerConstants.PERSISTENT_STORE_PROPERTY_NAME, "");
        }

        try {
            mqttBroker.startServer(new MemoryConfig(props));
            if (LOGGER.isDebugEnabled()) {
                if (wsEnabled) {
                    LOGGER.debugf("MQTT Broker started on tcp/%d and ws/%d (host=%s)", mqttPort, wsPort, host);
                } else {
                    LOGGER.debugf("MQTT Broker started on tcp/%d (host=%s). WebSocket disabled.", mqttPort, host);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to start MQTT Broker", e);
        }
    }

    @PreDestroy
    public void stop() {
        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Stopping MQTT Broker...");
        if (mqttBroker != null) {
            mqttBroker.stopServer();
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("MQTT Broker stopped");
        }
    }
}

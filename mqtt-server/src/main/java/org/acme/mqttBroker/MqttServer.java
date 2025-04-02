package org.acme.mqttBroker;

import java.io.IOException;
import java.util.Properties;

import org.jboss.logging.Logger;

import io.moquette.broker.Server;
import io.moquette.broker.config.MemoryConfig;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MqttServer {

    private static final Logger LOGGER = Logger.getLogger(MqttServer.class);

    private Server mqttBroker;

    @Inject
    private MqttConsumerService mqttConsumerService;

    @Startup(10)
    public void start() {
        LOGGER.info("Initializing MQTT Broker...");
        mqttBroker = new Server();
        Properties configProps = new Properties();
        configProps.setProperty("port", "1883");

        try {
            mqttBroker.startServer(new MemoryConfig(configProps));
            LOGGER.info("MQTT Broker started on port 1883");
        } catch (IOException e) {
            LOGGER.error("Failed to start MQTT Broker", e);
        }
        mqttConsumerService.init();
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

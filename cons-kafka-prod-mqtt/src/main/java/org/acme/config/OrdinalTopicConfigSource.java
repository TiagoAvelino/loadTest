package org.acme.config;

import java.util.Map;
import java.util.Set;

import jakarta.enterprise.inject.spi.CDI;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Computes KAFKA_TOPIC at runtime so SmallRye Kafka can use:
 * mp.messaging.incoming.kafka-channel.topic=${KAFKA_TOPIC}
 *
 * Formula:
 * target = (podOrdinal + startIndex) % brokerCount
 * KAFKA_TOPIC = "mqtt-service-" + target
 */
public class OrdinalTopicConfigSource implements ConfigSource {

    @Override
    public Map<String, String> getProperties() {
        return Map.of("KAFKA_TOPIC", computeTopic());
    }

    @Override
    public Set<String> getPropertyNames() {
        return Set.of("KAFKA_TOPIC");
    }

    @Override
    public String getValue(String propertyName) {
        if (!"KAFKA_TOPIC".equals(propertyName))
            return null;
        return computeTopic();
    }

    @Override
    public String getName() {
        return "OrdinalTopicConfigSource";
    }

    private String computeTopic() {
        // Access PodInfo and MP Config via CDI at runtime
        PodInfo pod = CDI.current().select(PodInfo.class).get();

        // Fall back to system/env if needed, but primarily use MP Config
        int brokerCount = getInt("app.shard.brokerCount", 1);
        int startIndex = getInt("app.shard.startIndex", 0);
        int target = positiveMod(pod.ordinal() + startIndex, brokerCount);
        return "mqtt-service-" + target;
    }

    private static int positiveMod(int x, int m) {
        if (m <= 0)
            return 0;
        int r = x % m;
        return r < 0 ? r + m : r;
    }

    private static int getInt(String key, int def) {
        String v = System.getProperty(key);
        if (v == null)
            v = System.getenv(key.replace('.', '_').toUpperCase());
        if (v == null)
            return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}

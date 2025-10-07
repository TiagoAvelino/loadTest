package org.acme.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PodInfo {
    private static final Logger LOG = Logger.getLogger(PodInfo.class);

    private final String podName;
    private final int ordinal;

    public PodInfo() {
        this.podName = System.getenv().getOrDefault("POD_NAME", "unknown-0");
        this.ordinal = parseOrdinal(podName);
        LOG.infof("PodInfo: name=%s ordinal=%d", podName, ordinal);
    }

    public String podName() {
        return podName;
    }

    public int ordinal() {
        return ordinal;
    }

    // StatefulSet pod names end with -<ordinal>, e.g., mqtt-producer-7
    private static int parseOrdinal(String name) {
        int i = name.lastIndexOf('-');
        if (i < 0 || i == name.length() - 1)
            return 0;
        try {
            return Integer.parseInt(name.substring(i + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

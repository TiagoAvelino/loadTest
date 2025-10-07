package org.acme.mqtt;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the MQTT host like the old Paho code:
 * host = "mqtt-server-" + <ordinal-from-POD_NAME suffix> + <SERVICE>
 *
 * and assigns it to:
 * mp.messaging.connector.smallrye-mqtt.host
 *
 * It also sanitizes SERVICE (strips scheme and :port) to avoid
 * "host:1883:1883".
 * If a port is detected inside SERVICE (or override), it sets the connector
 * port
 * ONLY when no explicit port is already configured.
 *
 * No SPI; runs early at runtime.
 */
@ApplicationScoped
@Startup(1)
public class MqttHostInitializer {

    private static final Logger LOG = Logger.getLogger(MqttHostInitializer.class);

    private static final String KEY_HOST = "mp.messaging.connector.smallrye-mqtt.host";
    private static final String KEY_PORT = "mp.messaging.connector.smallrye-mqtt.port";

    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");
    private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");
    private static final Pattern HOST_PORT = Pattern.compile("^([^\\[]*?)(?::(\\d+))?$"); // simple host[:port]

    @PostConstruct
    void init() {
        Config cfg = safeConfig();

        String podName = firstNonBlank(sysProp("POD_NAME"), env("POD_NAME"), cfgOpt(cfg, "POD_NAME"));
        String service = firstNonBlank(sysProp("SERVICE"), env("SERVICE"), cfgOpt(cfg, "SERVICE"));
        String override = firstNonBlank(sysProp("MQTT_HOST_OVERRIDE"), env("MQTT_HOST_OVERRIDE"),
                cfgOpt(cfg, "MQTT_HOST_OVERRIDE"));

        // 1) If override provided, use it directly; otherwise compute from pod+service.
        String computed = (override != null && !override.isBlank())
                ? override.trim()
                : computeFromPodAndService(podName, service);

        // 2) Normalize: strip scheme, trailing slashes, and peel out optional :port
        String withoutScheme = stripScheme(computed);
        String trimmed = stripTrailingSlashes(withoutScheme);
        HostAndPort hp = splitHostPort(trimmed);

        // 3) Set HOST
        System.setProperty(KEY_HOST, hp.host);
        // 4) If the port is embedded and user didn't explicitly set connector port, set
        // it
        if (hp.port != null && isBlank(effectiveConfigValue(cfg, KEY_PORT))) {
            System.setProperty(KEY_PORT, String.valueOf(hp.port));
        }

        LOG.debugf("MQTT resolved host='%s' port='%s' (from: POD_NAME=%s, SERVICE=%s, OVERRIDE=%s)",
                hp.host,
                effectiveConfigValue(cfg, KEY_PORT),
                podName, service, override == null ? "" : override);
    }

    private static String computeFromPodAndService(String podName, String service) {
        int idx = extractOrdinal(podName);
        String svc = service == null ? "" : service.trim();
        return "mqtt-server-" + idx + svc; // e.g. mqtt-server-0.mqtt-server-headless.kafka.svc.cluster.local
    }

    private static int extractOrdinal(String name) {
        if (name == null || name.isBlank())
            return 0;
        try {
            return Integer.parseInt(name.replaceAll(".*-(\\d+)$", "$1"));
        } catch (Exception ignored) {
            return 0;
        }
    }

    // ---- normalization helpers

    private static String stripScheme(String s) {
        if (s == null)
            return null;
        return SCHEME.matcher(s).replaceFirst("");
    }

    private static String stripTrailingSlashes(String s) {
        if (s == null)
            return null;
        return TRAILING_SLASHES.matcher(s).replaceAll("");
    }

    private static HostAndPort splitHostPort(String s) {
        if (s == null || s.isBlank())
            return new HostAndPort("localhost", null);
        // IPv6 with brackets [::1]:1883 not expected here; handle simplest host[:port]
        // case
        Matcher m = HOST_PORT.matcher(s);
        if (m.matches()) {
            String host = safeTrim(m.group(1));
            String port = safeTrim(m.group(2));
            Integer p = null;
            if (port != null && !port.isBlank()) {
                try {
                    p = Integer.parseInt(port);
                } catch (NumberFormatException ignored) {
                    /* ignore invalid */ }
            }
            return new HostAndPort(host, p);
        }
        return new HostAndPort(s, null);
    }

    private static String safeTrim(String v) {
        return v == null ? null : v.trim();
    }

    // ---- config access helpers

    private static Config safeConfig() {
        try {
            return ConfigProvider.getConfig();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String cfgOpt(Config cfg, String key) {
        try {
            return cfg == null ? null : cfg.getOptionalValue(key, String.class).orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String env(String key) {
        try {
            return System.getenv(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String sysProp(String key) {
        try {
            return System.getProperty(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null)
            return null;
        for (String v : vals) {
            if (v != null && !v.isBlank())
                return v;
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String effectiveConfigValue(Config cfg, String key) {
        // System properties win; then env; then config
        String v = sysProp(key);
        if (!isBlank(v))
            return v;
        String ev = env(toEnvKey(key));
        if (!isBlank(ev))
            return ev;
        return cfgOpt(cfg, key);
    }

    private static String toEnvKey(String key) {
        // crude MP config to env conversion, e.g.
        // mp.messaging.connector.smallrye-mqtt.port ->
        // MP_MESSAGING_CONNECTOR_SMALLRYE_MQTT_PORT
        return key.toUpperCase().replace('.', '_').replace('-', '_');
    }

    private record HostAndPort(String host, Integer port) {
    }
}

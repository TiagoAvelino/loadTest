package org.acme.util;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Lightweight provider for pod metadata without hitting the K8s API.
 * Kubernetes sets HOSTNAME=<pod-name> by default.
 */
@ApplicationScoped
public class PodInfo {

    private final String podName;
    private final String namespace;
    private final String podIP;

    public PodInfo() {
        // Fast-path: HOSTNAME is the pod name in Kubernetes/OpenShift
        this.podName = getenvOrDefault("POD_NAME",
                getenvOrDefault("HOSTNAME", "unknown-pod"));

        // These two are handy for logs/metrics; they may or may not be present by
        // default
        this.namespace = getenvOrDefault("POD_NAMESPACE",
                getenvOrDefault("NAMESPACE", "unknown-namespace"));

        // status.podIP is NOT set by default as an env; we try common fallbacks
        this.podIP = getenvOrDefault("POD_IP",
                getenvOrDefault("STATUS_POD_IP", "0.0.0.0"));
    }

    public String podName() {
        return podName;
    }

    public String namespace() {
        return namespace;
    }

    public String podIP() {
        return podIP;
    }

    private static String getenvOrDefault(String key, String dflt) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? dflt : v;
    }
}

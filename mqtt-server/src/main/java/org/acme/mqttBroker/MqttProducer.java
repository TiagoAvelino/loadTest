package org.acme.mqttBroker;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.SocketFactory;

import org.acme.tracing.TracingBridge;
import org.acme.tracing.messageparams.MqttSendMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.quarkus.runtime.Startup;

import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MqttProducer {

    private static final Logger LOGGER = Logger.getLogger(MqttProducer.class);
    private static final int DEFAULT_TCP_PORT = 1883;
    private static final int DEFAULT_SSL_PORT = 8883;
    private static final int QOS = 0;

    @Inject
    Tracer tracer;

    @ConfigProperty(name = "mqtt.url", defaultValue = "tcp://localhost:1883")
    String brokerUrl;

    @ConfigProperty(name = "mqtt.tracing.enabled", defaultValue = "true")
    boolean tracingEnabled;

    @ConfigProperty(name = "mqtt.keepalive.seconds", defaultValue = "20")
    int keepAliveSeconds;

    @ConfigProperty(name = "mqtt.connect.timeout.seconds", defaultValue = "2")
    int connectTimeoutSeconds;

    @ConfigProperty(name = "mqtt.max.inflight", defaultValue = "5000")
    int maxInflight;

    @ConfigProperty(name = "mqtt.socket.send.buf.bytes", defaultValue = "262144")
    int soSndBuf;

    @ConfigProperty(name = "mqtt.socket.recv.buf.bytes", defaultValue = "262144")
    int soRcvBuf;

    @ConfigProperty(name = "mqtt.producer.queue.capacity", defaultValue = "2048")
    int queueCapacity;

    @ConfigProperty(name = "mqtt.producer.parallelism", defaultValue = "2")
    int parallelism;

    private volatile String topic = "";

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    private volatile IMqttAsyncClient client;
    private volatile String currentBrokerHost;
    private volatile int currentBrokerPort;
    private volatile String currentBrokerScheme;
    private volatile String currentBrokerUrl;

    private volatile ExecutorService pubPool;
    private volatile BlockingQueue<Outgoing> outbound;
    private volatile boolean running;
    private volatile boolean initialized;

    private static final class Outgoing {
        final String topic;
        final MqttSendMessage msg;
        final Context parentCtx;
        final long enqNs;

        Outgoing(String topic, MqttSendMessage msg, Context parentCtx, long enqNs) {
            this.topic = topic;
            this.msg = msg;
            this.parentCtx = parentCtx;
            this.enqNs = enqNs;
        }
    }

    @PostConstruct
    @Startup(20)
    void init() {
        safeInitOnce();
    }

    @PreDestroy
    void shutdown() {
        running = false;
        try {
            if (client != null && client.isConnected())
                client.disconnect().waitForCompletion(1000);
        } catch (Exception ignored) {
        }
        try {
            if (client != null)
                client.close();
        } catch (Exception ignored) {
        }
        if (pubPool != null)
            pubPool.shutdownNow();
    }

    public void produce(MqttSendMessage mqttMes) {
        safeInitOnce();
        if (topic == null || topic.isBlank())
            throw new IllegalStateException("setTopic(...) before produce()");
        String effectiveUrl = Objects.requireNonNull(brokerUrl, "mqtt.url must not be null");
        ParsedUrl parsed = parseUrl(effectiveUrl);
        ensureConnected(parsed);

        Context parent = tracingEnabled ? Context.current() : null;
        if (!outbound.offer(new Outgoing(topic, mqttMes, parent, System.nanoTime()))) {
            LOGGER.warn("MQTT producer queue full; dropping (QoS 0)");
        }
    }

    private void safeInitOnce() {
        if (initialized)
            return;
        synchronized (this) {
            if (initialized)
                return;
            int n = Math.max(1, parallelism);
            pubPool = Executors.newFixedThreadPool(n, r -> {
                var t = new Thread(r, "mqtt-producer-pool");
                t.setDaemon(true);
                return t;
            });
            outbound = new ArrayBlockingQueue<>(Math.max(64, queueCapacity));
            running = true;
            for (int i = 0; i < n; i++)
                pubPool.submit(this::drainLoop);
            initialized = true;
        }
    }

    private static final class ParsedUrl {
        final String scheme, host, original;
        final int port;

        ParsedUrl(String scheme, String host, int port, String original) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.original = original;
        }
    }

    private ParsedUrl parseUrl(String url) {
        URI u = URI.create(url);
        String scheme = (u.getScheme() != null) ? u.getScheme().toLowerCase() : "tcp";
        String host = (u.getHost() != null) ? u.getHost() : u.getAuthority();
        if (host == null || host.isBlank())
            throw new IllegalArgumentException("missing host in mqtt.url: " + url);
        int port = (u.getPort() >= 0) ? u.getPort()
                : (("ssl".equals(scheme) || "wss".equals(scheme)) ? DEFAULT_SSL_PORT : DEFAULT_TCP_PORT);
        if ("mqtt".equals(scheme))
            scheme = "tcp";
        if ("mqtts".equals(scheme) || "tls".equals(scheme))
            scheme = "ssl";
        return new ParsedUrl(scheme, host, port, url);
    }

    private synchronized void ensureConnected(ParsedUrl p) {
        try {
            if (client != null && p.host.equals(currentBrokerHost) && p.port == currentBrokerPort
                    && p.scheme.equals(currentBrokerScheme) && client.isConnected())
                return;

            if (client == null || !p.host.equals(currentBrokerHost) || p.port != currentBrokerPort
                    || !p.scheme.equals(currentBrokerScheme)) {
                if (client != null)
                    try {
                        client.close();
                    } catch (Exception ignored) {
                    }
                client = new MqttAsyncClient(p.original, MqttAsyncClient.generateClientId(), new MemoryPersistence());
                currentBrokerHost = p.host;
                currentBrokerPort = p.port;
                currentBrokerScheme = p.scheme;
                currentBrokerUrl = p.original;
            }

            if (!client.isConnected()) {
                MqttConnectOptions opts = new MqttConnectOptions();
                opts.setCleanSession(true);
                opts.setAutomaticReconnect(true);
                opts.setKeepAliveInterval(Math.max(10, keepAliveSeconds));
                opts.setConnectionTimeout(Math.max(1, connectTimeoutSeconds));
                opts.setMaxInflight(Math.max(20, maxInflight)); // <-- mqtt.max.inflight
                opts.setSocketFactory(new TcpNoDelaySocketFactory( // <-- socket buffers + TCP_NODELAY
                        opts.getConnectionTimeout(), Math.max(0, soSndBuf), Math.max(0, soRcvBuf)));

                DisconnectedBufferOptions dbo = new DisconnectedBufferOptions();
                dbo.setBufferEnabled(false);
                client.setBufferOpts(dbo);

                IMqttToken tok = client.connect(opts);
                tok.waitForCompletion(Math.max(1000, opts.getConnectionTimeout() * 1000 + 500));
                LOGGER.infof("MQTT producer connected %s (inflight=%d snd=%d rcv=%d)", currentBrokerUrl,
                        opts.getMaxInflight(), soSndBuf, soRcvBuf);
            }
        } catch (MqttException e) {
            LOGGER.warnf(e, "MQTT connect failed: %s (code %d)", e.getMessage(), e.getReasonCode());
        }
    }

    private void drainLoop() {
        while (running) {
            try {
                Outgoing out = outbound.take();
                IMqttAsyncClient c = this.client;
                if (c == null || !c.isConnected())
                    continue;

                if (tracingEnabled) {
                    Span span = tracer.spanBuilder("mqtt.send")
                            .setSpanKind(SpanKind.PRODUCER)
                            .setParent(out.parentCtx != null ? out.parentCtx : Context.root())
                            .setAttribute("messaging.system", "mqtt")
                            .setAttribute("messaging.destination_kind", "topic")
                            .setAttribute("messaging.destination", out.topic)
                            .setAttribute("mqtt.qos", QOS)
                            .setAttribute("mqtt.producer.enqueue_delay_ms",
                                    (System.nanoTime() - out.enqNs) / 1_000_000.0)
                            .startSpan();
                    long t0 = System.nanoTime();
                    try (Scope ignored = span.makeCurrent()) {
                        TracingBridge.injectIntoMessage(out.msg);
                        byte[] payload = out.msg.serialize();
                        c.publish(out.topic, payload, QOS, false, null, new IMqttActionListener() {
                            @Override
                            public void onSuccess(IMqttToken token) {
                                span.setAttribute("messaging.publish_socket_ms",
                                        (System.nanoTime() - t0) / 1_000_000.0);
                                span.setStatus(StatusCode.OK);
                                span.end();
                            }

                            @Override
                            public void onFailure(IMqttToken token, Throwable ex) {
                                span.recordException(ex);
                                span.setStatus(StatusCode.ERROR, "publish failure");
                                span.end();
                            }
                        });
                    }
                } else {
                    c.publish(out.topic, out.msg.serialize(), QOS, false, null, null);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                LOGGER.warn("Error in MQTT publish loop", t);
            }
        }
    }

    /** Socket factory: TCP_NODELAY + send/recv buffers + connect timeouts. */
    static final class TcpNoDelaySocketFactory extends SocketFactory {
        private final int timeoutMs, sndBuf, rcvBuf;

        TcpNoDelaySocketFactory(int timeoutSeconds, int sndBuf, int rcvBuf) {
            this.timeoutMs = Math.max(1000, timeoutSeconds * 1000);
            this.sndBuf = sndBuf;
            this.rcvBuf = rcvBuf;
        }

        private Socket newSocket() {
            try {
                Socket s = new Socket();
                s.setTcpNoDelay(true);
                if (sndBuf > 0)
                    s.setSendBufferSize(sndBuf);
                if (rcvBuf > 0)
                    s.setReceiveBufferSize(rcvBuf);
                return s;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Socket createSocket() {
            return newSocket();
        }

        @Override
        public Socket createSocket(String host, int port) throws java.io.IOException {
            Socket s = newSocket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return s;
        }

        @Override
        public Socket createSocket(String host, int port, java.net.InetAddress lh, int lp) throws java.io.IOException {
            Socket s = newSocket();
            s.bind(new java.net.InetSocketAddress(lh, lp));
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return s;
        }

        @Override
        public Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            Socket s = newSocket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return s;
        }

        @Override
        public Socket createSocket(java.net.InetAddress addr, int port, java.net.InetAddress la, int lp)
                throws java.io.IOException {
            Socket s = newSocket();
            s.bind(new java.net.InetSocketAddress(la, lp));
            s.connect(new InetSocketAddress(addr, port), timeoutMs);
            return s;
        }
    }
}

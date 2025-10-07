package org.acme.mqtt;

import java.util.HashMap;
import java.util.Map;

import org.acme.tracing.TracingBridge;
import org.acme.tracing.messageparams.MqttSendMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.netty.handler.codec.mqtt.MqttQoS;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.mqtt.MqttMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ReactiveMqttProducer {

    private static final Logger LOG = Logger.getLogger(ReactiveMqttProducer.class);

    @Inject
    Tracer tracer;

    @Channel("mqtt-out")
    MutinyEmitter<MqttMessage<byte[]>> emitter;

    @Inject
    ObjectMapper mapper;

    @Inject
    @ConfigProperty(name = "mqtt.tracing.enabled", defaultValue = "true")
    boolean tracingEnabled;

    /**
     * DEBUG version: logs JSON *after* injection and logs the topic actually used.
     */
    public void publish(String topic, MqttSendMessage payload) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("Topic must not be null/blank");
        }

        Span sendSpan = tracer.spanBuilder("mqtt.send")
                .setSpanKind(SpanKind.PRODUCER)
                .setAttribute("messaging.system", "mqtt")
                .setAttribute("messaging.operation", "publish")
                .setAttribute("messaging.destination_kind", "topic")
                .setAttribute("messaging.destination", topic)
                .startSpan();

        try (Scope ignored = sendSpan.makeCurrent()) {

            // 1) Inject trace context into payload
            if (tracingEnabled) {
                try {
                    TracingBridge.injectIntoMessage(payload);
                } catch (Throwable ignore) {
                    Map<String, String> carrier = new HashMap<>(2);
                    GlobalOpenTelemetry.getPropagators()
                            .getTextMapPropagator()
                            .inject(io.opentelemetry.context.Context.current(), carrier, Map::put);
                    payload.setTraceParent(carrier.get("traceparent"));
                    payload.setTraceState(carrier.get("tracestate"));
                }
            }

            // 2) Serialize with Jackson (do NOT use toString())
            byte[] json = mapper.writeValueAsBytes(payload);

            // DEBUG: show what we are really sending
            if (LOG.isDebugEnabled()) {
                LOG.debugf("PRODUCER → topic='%s' json=%s", topic,
                        new String(json, java.nio.charset.StandardCharsets.UTF_8));
                LOG.debugf("PRODUCER traceparent in object: %s", payload.getTraceParent());
            }

            // 3) Build MQTT message with the *dynamic* topic
            MqttMessage<byte[]> msg = MqttMessage.of(topic, json, MqttQoS.AT_MOST_ONCE, false);

            // 4) Fire and log result
            emitter.send(msg).subscribe().with(
                    ok -> {
                        sendSpan.setStatus(StatusCode.OK);
                        sendSpan.end();
                    },
                    err -> {
                        sendSpan.recordException(err);
                        sendSpan.setStatus(StatusCode.ERROR, String.valueOf(err.getMessage()));
                        sendSpan.end();
                    });

        } catch (Exception e) {
            sendSpan.recordException(e);
            sendSpan.setStatus(StatusCode.ERROR, "serialize/publish exception");
            sendSpan.end();
            LOG.error("ReactiveMqttProducer.publish failed", e);
        }
    }
}

package org.acme.tracing.messageparams;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@RegisterForReflection // ensure available in native
public class MqttSendMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private String jwt;
    private String message;
    private String host;
    private Boolean isThereAny = false;
    private String traceParent;
    private String traceState;

    // --- Timing fields added for end-to-end measurement ---
    // Cross-JVM comparable (based on wall clock)
    private long sentEpochMs;
    // Only comparable within the same JVM (for debugging/reference)
    private long sentNano;

    // explicit public no-arg ctor for reflective instantiation
    public MqttSendMessage() {
    }

    public byte[] serialize() {
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream)) {
                objectOutputStream.writeObject(this);
                objectOutputStream.flush();
            }
            return byteArrayOutputStream.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            return null; // keep behavior; alternatively throw a custom SerializationException
        }
    }
}

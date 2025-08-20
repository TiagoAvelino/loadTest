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
            // keep behavior, but you can switch to throwing SerializationException if you
            // prefer
            e.printStackTrace();
            return null;
        }
    }
}

package org.acme.tracing.messageparams;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.Map;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

@RegisterForReflection
public class MqttSendMessageSerializer implements Serializer<MqttSendMessage> {

    public MqttSendMessageSerializer() {
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // No-op
    }

    @Override
    public byte[] serialize(String topic, MqttSendMessage data) {
        if (data == null) {
            return null;
        }
        try {
            // Use the model's helper if you prefer:
            // return data.serialize();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(data);
                oos.flush();
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new SerializationException("Failed to serialize MqttSendMessage for topic " + topic, e);
        }
    }

    @Override
    public void close() {
        // No resources
    }
}

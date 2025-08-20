package org.acme.tracing.messageparams;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

@RegisterForReflection
public class MqttSendMessageKafkaDeserializer implements Deserializer<MqttSendMessage> {

    public MqttSendMessageKafkaDeserializer() {
        // public no-arg required
    }

    @Override
    public MqttSendMessage deserialize(String topic, byte[] data) {
        if (data == null) {
            return null; // Kafka semantics allow nulls
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                ObjectInputStream ois = new ObjectInputStream(bais)) {
            Object obj = ois.readObject();
            if (obj instanceof MqttSendMessage) {
                return (MqttSendMessage) obj;
            }
            throw new SerializationException(
                    "Unexpected type " + (obj == null ? "null" : obj.getClass())
                            + " when deserializing topic " + topic);
        } catch (Exception e) {
            throw new SerializationException("Failed to deserialize MqttSendMessage from topic " + topic, e);
        }
    }
}

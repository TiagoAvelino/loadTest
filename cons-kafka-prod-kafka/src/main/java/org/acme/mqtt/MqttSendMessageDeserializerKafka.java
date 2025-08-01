package org.acme.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Deserializer;

import java.util.Map;

public class MqttSendMessageDeserializerKafka implements Deserializer<MqttSendMessage> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
    }

    @Override
    public MqttSendMessage deserialize(String topic, byte[] data) {
        try {
            return objectMapper.readValue(data, MqttSendMessage.class);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public void close() {
    }
}
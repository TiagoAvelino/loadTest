package org.acme.mqtt;

import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

public class MqttSendMessageDeserializerKafka extends ObjectMapperDeserializer<MqttSendMessage> {
    public MqttSendMessageDeserializerKafka() {
        super(MqttSendMessage.class);
    }
}
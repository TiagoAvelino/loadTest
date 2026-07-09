package org.acme.kafka;

import org.acme.tracing.messageparams.MqttSendMessage;
import io.quarkus.kafka.client.serialization.ObjectMapperSerde;
import org.apache.kafka.common.serialization.Serde;

public class SerdesFactory {

    public static Serde<MqttSendMessage> mqttSendMessage() {
        // if you need to customize the mapper, you can do so here
        return new ObjectMapperSerde<>(MqttSendMessage.class);
    }
}
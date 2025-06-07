package org.acme.kafka;

import java.time.Duration;

import org.acme.mqtt.MqttSendMessage;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.state.WindowStore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class Last5sTopology {

    @Produces
    public Topology build() {
        StreamsBuilder builder = new StreamsBuilder();
        builder.<String, MqttSendMessage>stream("app.test.window")
                .groupByKey()
                .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofSeconds(5)))
                .reduce((oldV, newV) -> newV,
                        Materialized.<String, MqttSendMessage, WindowStore<Bytes, byte[]>>as("last5s-store")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(SerdesFactory.mqttSendMessage()));
        return builder.build();
    }
}

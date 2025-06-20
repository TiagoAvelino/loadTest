// package org.acme.kafka;

// import java.time.Instant;
// import java.util.Optional;

// import org.acme.mqtt.MqttSendMessage;
// import org.apache.kafka.streams.KafkaStreams;
// import org.apache.kafka.streams.KeyValue;
// import org.apache.kafka.streams.StoreQueryParameters;
// import org.apache.kafka.streams.state.QueryableStoreTypes;
// import org.apache.kafka.streams.state.ReadOnlyWindowStore;
// import org.apache.kafka.streams.state.WindowStoreIterator;

// import jakarta.enterprise.context.ApplicationScoped;
// import jakarta.inject.Inject;

// @ApplicationScoped
// public class Last5sLookup {

// @Inject // provided by quarkus-kafka-streams extension
// KafkaStreams streams;

// public Optional<MqttSendMessage> findLatest(String key) {
// ReadOnlyWindowStore<String, MqttSendMessage> store =
// streams.store(StoreQueryParameters.fromNameAndType(
// "last5s-store",
// QueryableStoreTypes.windowStore()));

// Instant now = Instant.now();
// try (WindowStoreIterator<MqttSendMessage> it = store.fetch(key,
// now.minusSeconds(5), now)) {

// if (!it.hasNext()) {
// return Optional.empty();
// }
// KeyValue<Long, MqttSendMessage> kv = it.next();
// while (it.hasNext())
// kv = it.next(); // newest
// return Optional.ofNullable(kv.value);
// }
// }
// }
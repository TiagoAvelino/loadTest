curl -X POST "http://mqtt-producer-kafka.apps.tiago-cluster.sandbox1433.opentlc.com/mqtt/send?topic=mqtt-message-in/1/2/app/test" \
 -H "Content-Type: application/json" \
 -H "Authorization: Bearer my-token" \
 -d '{"key": "value"}'

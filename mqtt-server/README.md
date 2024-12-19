curl -X POST "http://mqtt-producer-horus.apps.meu-cluster.sandbox868.opentlc.com/mqtt/send?topic=mqtt-message-in/1/2/app/test" \
 -H "Content-Type: application/json" \
 -H "Authorization: Bearer my-token" \
 -d '{"key": "value"}'

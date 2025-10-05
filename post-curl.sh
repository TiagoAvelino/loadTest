#!/bin/bash

counter=1

while true
do
  # Generate a dynamic topic using the counter
  
  # Print the dynamic topic
  echo "Dynamic topic: $dynamic_topic"

  # Send the POST request with the dynamic topic   curl -X POST "http://mqtt-producer-kafka.apps.tiago-cluster.sandbox2008.opentlc.com/mqtt/send?topic=mqtt-message-in/1/2/app/test" \

  curl -X POST "http://mqtt-producer-kafka.apps.tiago-cluster.sandbox5437.opentlc.com/mqtt/send?topic=mqtt-message-in/1/2/app/test/pull" \
    -H "Content-Type: application/json" \
    -H "Accept: */*" \
    -d "{
          \"message\": \"teste$counter\",
          \"jwt\": \"teste\"
        }"
  
  #  curl -X POST "http://mqtt-producer-kafka.apps.tiago.tiago.to/mqtt/send?topic=mqtt-message-in/1/2/app/test" \
  #  -H "Content-Type: application/json" \
  #  -H "Accept: */*" \
  #  -d "{
  #        \"message\": \"teste\",
  #        \"jwt\": \"teste\"
  #      }"
  

  # curl -X POST "http://mqtt-producer-kafka.apps.tiago-cluster.sandbox1433.opentlc.com/mqtt/send?topic=mqtt-message-in/1/2/app/test"  -H "Content-Type: application/json"  -d '{ "message": "teste","jwt": "teste"}'
  # Increment the counter for each request
  counter=$((counter + 1))
  
  # Wait for 1 second before sending the next request
  sleep 0.01
done

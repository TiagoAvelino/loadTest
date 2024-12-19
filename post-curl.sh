#!/bin/bash

counter=1

while true
do
  # Generate a dynamic topic using the counter
  dynamic_topic="mqtt-message-in/1/$counter/app/test"
  
  # Print the dynamic topic
  echo "Dynamic topic: $dynamic_topic"

  # Send the POST request with the dynamic topic
  curl -X POST "http://localhost:8082/mqtt/send?topic=$dynamic_topic" \
    -H "Content-Type: application/json" \
    -H "Accept: */*" \
    -d "{
          \"message\": \"teste$counter\",
          \"jwt\": \"teste\"
        }"
  
  # Increment the counter for each request
  counter=$((counter + 1))
  
  # Wait for 1 second before sending the next request
  sleep 0.1
done

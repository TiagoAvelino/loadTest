#!/bin/bash
#Vars
namespace=kafka
# Applying OpenTelemetry
oc new-project openshift-tempo-operator
oc apply -f opentelemetry/collector.yaml -n openshift-tempo-operator

oc apply -f opentelemetry/minio.yaml -n minio

oc apply -f secret-minio.yaml -n openshift-tempo-operator
oc apply -f tempo.yaml -n openshift-tempo-operator

#Secrets for Kafka-User
oc create secret generic kafka-auth --from-literal username=redhat-user --from-literal password=redhat123 -n $namespace
oc get secret my-cluster-cluster-ca-cert -n kafka -o jsonpath='{.data.ca\.crt}' | base64 -d > ca.crt -n $namespace
keytool -import -file ca.crt -alias ca -keystore truststore.jks -storepass redhat -noprompt -n $namespace
oc create secret generic kafka-client-ssl-secret --from-file=truststore.jks --from-literal truststore-password=redhat -n $namespace

#Applying deployments
oc apply -f mqtt-producer/src/main/k8s/deployment.yaml -n $namespace
oc apply -f mqtt-server/src/main/k8s/deployment.yaml -n $namespace
oc apply -f cons-kafka-prod-kafka/src/main/k8s/deployment.yaml -n $namespace
oc apply -f cons-kafka-prod-mqtt/src/main/k8s/deployment.yaml -n $namespace

#Applying services
oc apply -f mqtt-producer/src/main/k8s/deployment.yaml -n $namespace
oc apply -f mqtt-server/src/main/k8s/deployment.yaml -n $namespace
oc apply -f cons-kafka-prod-kafka/src/main/k8s/deployment.yaml -n $namespace
oc apply -f cons-kafka-prod-mqtt/src/main/k8s/deployment.yaml -n $namespace

#!/usr/bin/env bash
set -euo pipefail

# Namespace for your Kafka-related apps
namespace="kafka"

# 1. OpenTelemetry setup
# Try to create the project; if it already exists, switch to it.
oc new-project openshift-tempo-operator || oc project openshift-tempo-operator
oc apply -f opentelemetry/collector.yaml -n openshift-tempo-operator

# If you have a separate 'minio' project for storage:
oc new-project minio || oc project minio
oc apply -f opentelemetry/minio.yaml -n minio

# Back to tempo operator project for any secrets and Tempo itself
oc project openshift-tempo-operator
oc apply -f secret-minio.yaml -n openshift-tempo-operator
oc apply -f tempo.yaml -n openshift-tempo-operator

# 2. Kafka user secrets
# Ensure the kafka namespace exists or switch to it
oc new-project "$namespace" || oc project "$namespace"

# Create/update a basic username/password secret
oc create secret generic kafka-auth \
  --from-literal=username=redhat-user \
  --from-literal=password=redhat123 \
  --dry-run=client -o yaml | oc apply -f - -n "$namespace"

# Extract the cluster CA cert from the Kafka operator secret, build a Java truststore, then store it as a secret
oc get secret my-cluster-cluster-ca-cert -n "$namespace" -o jsonpath='{.data.ca\.crt}' | base64 -d > ca.crt
keytool -import -trustcacerts -alias kafka-ca \
  -file ca.crt \
  -keystore truststore.jks \
  -storepass redhat \
  -noprompt
oc create secret generic kafka-client-ssl-secret \
  --from-file=truststore.jks \
  --from-literal=truststore-password=redhat \
  --dry-run=client -o yaml | oc apply -f - -n "$namespace"
rm -f ca.crt truststore.jks

# 3. Deploy applications
# For each component, apply its deployment YAML. If you have service YAMLs, apply them too.
for comp in mqtt-producer mqtt-server cons-kafka-prod-kafka cons-kafka-prod-mqtt; do
  deploy_path="$comp/src/main/k8s/deployment.yaml"
  if [[ -f "$deploy_path" ]]; then
    oc apply -f "$deploy_path" -n "$namespace"
  else
    echo "Warning: $deploy_path not found; skip."
  fi

  # If you have a service YAML alongside:
  svc_path="$comp/src/main/k8s/service.yaml"
  if [[ -f "$svc_path" ]]; then
    oc apply -f "$svc_path" -n "$namespace"
  fi
done

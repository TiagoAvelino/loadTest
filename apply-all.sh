#!/usr/bin/env bash
set -euo pipefail
# Namespace for your Kafka-related apps
namespace="kafka"

# --- 1) OpenTelemetry / Tempo setup ---
# Apply the collector in the Tempo operator project
oc apply -f opentelemetry/collector.yaml -n openshift-tempo-operator

# If you have a separate 'minio' project for storage:
oc new-project minio || oc project minio
oc apply -f opentelemetry/minio.yaml -n minio

# Back to tempo operator project for any secrets and Tempo itself
oc project openshift-tempo-operator
oc apply -f opentelemetry/secret-tempo.yaml -n openshift-tempo-operator
oc apply -f opentelemetry/tempo.yaml -n openshift-tempo-operator

# --- 2) Kafka / Strimzi resources and client creds ---
# Ensure the kafka namespace exists or switch to it
oc new-project "$namespace" || oc project "$namespace"

oc apply -f kafka/kafka-metrics-cm.yaml  -n "$namespace"
oc apply -f kafka/kafka-cr.yaml          -n "$namespace"
oc apply -f kafka/kafka-topic.yaml       -n "$namespace"
oc apply -f kafka/kafka-topic-push.yaml  -n "$namespace"
oc apply -f kafka/kafka-user.yaml        -n "$namespace"
oc apply -f kafka/kafka-user-password.yaml -n "$namespace"
oc apply -f kafka/console-kafka.yaml
# Create/update a basic username/password secret (demo creds)
oc create secret generic kafka-auth \
  --from-literal=username=redhat-user \
  --from-literal=password=redhat123 \
  --dry-run=client -o yaml | oc apply -f - -n "$namespace"

# Build a Java truststore from the Kafka cluster CA and store as a secret
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

# --- 3) Deploy applications ---
# For mqtt-producer and mqtt-server: USE STATEFULSET MANIFESTS (ss.yaml / ss-service.yaml)
# For other components, use deployment.yaml / service.yaml if present.

deploy_component() {
  local comp="$1"
  local base_path="$comp/src/main/k8s"

  case "$comp" in
    mqtt-producer|mqtt-server)
      # Apply StatefulSet and its Service
      if [[ -f "$base_path/ss.yaml" ]]; then
        oc apply -f "$base_path/ss.yaml" -n "$namespace"
      else
        echo "Warning: $base_path/ss.yaml not found; skipping $comp StatefulSet."
      fi
      if [[ -f "$base_path/ss-service.yaml" ]]; then
        oc apply -f "$base_path/ss-service.yaml" -n "$namespace"
      else
        echo "Warning: $base_path/ss-service.yaml not found; no Service applied for $comp."
      fi
      ;;
    *)
      # Default: apply Deployment + Service if they exist
      if [[ -f "$base_path/deployment.yaml" ]]; then
        oc apply -f "$base_path/deployment.yaml" -n "$namespace"
      else
        echo "Warning: $base_path/deployment.yaml not found; skip Deployment for $comp."
      fi
      if [[ -f "$base_path/service.yaml" ]]; then
        oc apply -f "$base_path/service.yaml" -n "$namespace"
      fi
      ;;
  esac
}

# List all components you want to deploy
for comp in mqtt-producer mqtt-server cons-kafka-prod-kafka cons-kafka-prod-mqtt; do
  deploy_component "$comp"
done

# Expose the mqtt-producer service (adjust if service name differs in ss-service.yaml)
oc expose svc/mqtt-producer -n "$namespace" || echo "Note: Could not expose svc/mqtt-producer (may already be exposed or named differently)."

# --- 4) Install k6 operator ---
echo "Installing k6 operator..."
# Apply operator bundle into its system namespace
oc new-project k6-operator-system || oc project k6-operator-system
curl -s https://raw.githubusercontent.com/grafana/k6-operator/main/bundle.yaml | kubectl apply -f -

# --- 5) Create ConfigMap for k6 tests ---
echo "Creating ConfigMap for k6 load test..."
# Ensure we’re in the correct namespace for the operator’s default resources
oc project k6-operator-system

# Create the ConfigMap from local file k6/load-test.js (adjust the path if needed)
if [[ -d "k6" ]]; then
  pushd "k6" >/dev/null
  if [[ -f "load-test.js" ]]; then
    oc create configmap k6-api-test --from-file load-test.js -n k6-operator-system \
      --dry-run=client -o yaml | oc apply -f -
  else
    echo "Warning: k6/load-test.js not found; skipping k6 ConfigMap creation."
  fi
  popd >/dev/null
else
  echo "Warning: ./k6 directory not found; skipping k6 ConfigMap creation."
fi

echo "All done."
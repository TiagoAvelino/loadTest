#!/usr/bin/env bash
set -euo pipefail
# Namespace for your Kafka-related apps
namespace="kafka"

# Allow skipping operator installation via environment variable
SKIP_OPERATORS="${SKIP_OPERATORS:-false}"

# Function to check if catalog source is available
check_catalog_source() {
  local catalog_name="${1:-redhat-operators}"
  local catalog_namespace="${2:-openshift-marketplace}"
  
  echo "Checking catalog source availability..."
  if oc get catalogsource "$catalog_name" -n "$catalog_namespace" >/dev/null 2>&1; then
    local status=$(oc get catalogsource "$catalog_name" -n "$catalog_namespace" -o jsonpath='{.status.connectionState.lastObservedState}' 2>/dev/null || echo "")
    if [ "$status" = "READY" ]; then
      echo "✓ Catalog source $catalog_name is ready"
      return 0
    else
      echo "⚠ Catalog source $catalog_name exists but status is: $status"
      return 1
    fi
  else
    echo "⚠ Catalog source $catalog_name not found in $catalog_namespace"
    return 1
  fi
}

# Function to apply subscription with retry
apply_subscription_with_retry() {
  local subscription_file="$1"
  local max_retries=3
  local retry=0
  
  while [ $retry -lt $max_retries ]; do
    if oc apply -f "$subscription_file" 2>&1; then
      echo "✓ Successfully applied $subscription_file"
      return 0
    else
      local exit_code=$?
      retry=$((retry + 1))
      if [ $retry -lt $max_retries ]; then
        echo "⚠ Failed to apply $subscription_file (attempt $retry/$max_retries), retrying in 10 seconds..."
        sleep 10
      else
        echo "✗ Failed to apply $subscription_file after $max_retries attempts"
        echo "  This may be due to catalog source connectivity issues."
        echo "  You can manually install operators from the OpenShift Console -> OperatorHub"
        return $exit_code
      fi
    fi
  done
}

# Function to wait for operator to be ready
wait_for_operator() {
  local subscription_name="$1"
  local max_wait=600  # 10 minutes max
  local elapsed=0
  local interval=10
  
  echo "Waiting for $subscription_name operator to be ready..."
  
  while [ $elapsed -lt $max_wait ]; do
    # Check if CSV exists and is in Succeeded phase
    local csv_status=$(oc get csv -n openshift-operators -o jsonpath='{.items[*].status.phase}' 2>/dev/null | grep -o "Succeeded" || echo "")
    local subscription_installed=$(oc get subscription "$subscription_name" -n openshift-operators -o jsonpath='{.status.state}' 2>/dev/null || echo "")
    
    if [ "$subscription_installed" = "AtLatestKnown" ] || [ -n "$csv_status" ]; then
      echo "✓ $subscription_name operator is ready"
      return 0
    fi
    echo "  Waiting for $subscription_name operator... (${elapsed}s/${max_wait}s)"
    sleep $interval
    elapsed=$((elapsed + interval))
  done
  
  echo "⚠ Warning: $subscription_name operator may not be fully ready (timeout after ${max_wait}s)"
  echo "  Continuing anyway - operators may still be installing in the background"
  return 1
}

# --- 0) Install required operators ---
if [ "$SKIP_OPERATORS" = "true" ]; then
  echo "=== Skipping operator installation (SKIP_OPERATORS=true) ==="
else
  echo "=== Installing required operators ==="

  # Ensure openshift-operators namespace exists
  oc get namespace openshift-operators >/dev/null 2>&1 || oc create namespace openshift-operators

  # Check catalog source availability (non-blocking)
  check_catalog_source "redhat-operators" "openshift-marketplace" || {
    echo "⚠ Catalog source may not be ready. Will attempt to apply subscriptions anyway."
    echo "  If subscriptions fail, you may need to wait for the catalog source to be ready"
    echo "  or install operators manually from the OpenShift Console."
    echo "  You can also set SKIP_OPERATORS=true to skip operator installation."
  }

  # Apply operator subscriptions with retry logic
  echo "Applying operator subscriptions..."
  apply_subscription_with_retry operators/strimzi-subscription.yaml || true
  apply_subscription_with_retry operators/opentelemetry-subscription.yaml || true
  apply_subscription_with_retry operators/tempo-subscription.yaml || true
  apply_subscription_with_retry operators/kafka-console.yaml || true

  # Wait for operators to be installed (non-blocking, but will warn if not ready)
  wait_for_operator "amq-streams" || true
  wait_for_operator "opentelemetry-product" || true
  wait_for_operator "tempo-operator-product" || true
  wait_for_operator "amq-streams-console" || true
  echo "=== Operators installation initiated ==="
  echo ""
fi

# --- 1) OpenTelemetry / Tempo setup ---
# Apply the collector in the Tempo operator project
oc apply -f opentelemetry/collector.yaml -n openshift-tempo-operator

# If you have a separate 'minio' project for storage:
oc new-project minio || oc project minio
oc apply -f opentelemetry/minio.yaml -n minio
# --- Create MinIO bucket "tempo" via Route (no pod) ---
echo "Creating MinIO bucket via Route (no pod)..."

# Read creds from secret
MINIO_BUCKET="$(oc -n minio get secret minio-credentials -o jsonpath='{.data.bucket}' | base64 -d)"
MINIO_ACCESS_KEY="$(oc -n minio get secret minio-credentials -o jsonpath='{.data.access_key_id}' | base64 -d)"
MINIO_SECRET_KEY="$(oc -n minio get secret minio-credentials -o jsonpath='{.data.access_key_secret}' | base64 -d)"

if [[ -z "${MINIO_BUCKET}" || -z "${MINIO_ACCESS_KEY}" || -z "${MINIO_SECRET_KEY}" ]]; then
  echo "✗ Missing required fields in secret minio-credentials (bucket/access_key_id/access_key_secret)"
  exit 1
fi

# Ensure a Route exists to MinIO service
ROUTE_NAME="minio-api"
SVC_NAME="minio-service"
SVC_PORT="9000"

if ! oc -n minio get route "${ROUTE_NAME}" >/dev/null 2>&1; then
  echo "Route ${ROUTE_NAME} not found; creating it for service ${SVC_NAME}:${SVC_PORT}..."
  oc -n minio create route edge "${ROUTE_NAME}" --service="${SVC_NAME}" --port="${SVC_PORT}" || true
fi

MINIO_HOST="$(oc -n minio get route "${ROUTE_NAME}" -o jsonpath='{.spec.host}')"
if [[ -z "${MINIO_HOST}" ]]; then
  echo "✗ Could not determine MinIO Route host"
  exit 1
fi

MINIO_ROUTE_ENDPOINT="https://${MINIO_HOST}"

# If your MinIO is NOT behind TLS on the route, use http instead:
# MINIO_ROUTE_ENDPOINT="http://${MINIO_HOST}"

# Create bucket using local mc binary
# Using --insecure flag to bypass certificate verification for OpenShift self-signed certificates
echo "Setting up MinIO alias..."
mc alias set myminio "${MINIO_ROUTE_ENDPOINT}" "${MINIO_ACCESS_KEY}" "${MINIO_SECRET_KEY}" --insecure

echo "Testing access (list buckets)..."
mc --insecure ls myminio

echo "Creating bucket '${MINIO_BUCKET}'..."
mc --insecure mb --ignore-existing "myminio/${MINIO_BUCKET}"

echo "✓ Bucket '${MINIO_BUCKET}' ensured via ${MINIO_ROUTE_ENDPOINT}"

echo "✓ Bucket '${MINIO_BUCKET}' ensured via ${MINIO_ROUTE_ENDPOINT}"

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

# Create WebSocket route for mqtt-server
if [[ -f "mqtt-server/src/main/k8s/route-ws.yaml" ]]; then
  echo "Creating WebSocket route for mqtt-server..."
  oc apply -f mqtt-server/src/main/k8s/route-ws.yaml -n "$namespace"
  echo "✓ WebSocket route created. Host: $(oc get route mqtt-server-ws -n "$namespace" -o jsonpath='{.spec.host}' 2>/dev/null || echo 'check with: oc get route mqtt-server-ws -n kafka')"
else
  echo "Warning: mqtt-server/src/main/k8s/route-ws.yaml not found; skipping WebSocket route creation."
fi

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
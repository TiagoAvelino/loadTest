const mqtt = require("mqtt");

const ROUTE_HOST = "mqtt-server.apps.tiago-cluster.sandbox1923.opentlc.com";
const PATH = "/mqtt";           // change if your WS listener uses a different path
const TOPIC = "horus/api/200/external/MOV/123/456/monitor/state/pull/value";

const payload = {
  fields: [
    { name: "bundleName", value: "mov-react-native-login" },
    { name: "screenName", value: "HomeTabContainer" },
    { name: "appVersion", value: "9.84.1.0" },
    { name: "OSName", value: "android" },
    { name: "saldo", value: "0,00" },
    { name: "isScreenReaderEnabled", value: "N" },
    { name: "URL", value: "Mobile_PF" },
    { name: "channel", value: "MOV" },
    { name: "appChannel", value: "MOV" },
    { name: "OSVersion", value: "27" },
    { name: "ambienteLogado", value: "true" },
    { name: "nomePersonalizado", value: "Felipe" },
    { name: "horusVersion", value: "4.27.1" },
    { name: "horusVersionDouble", value: "4027.0009999999997" },
    { name: "appVersionDouble", value: "9084.001" },
    { name: "navMov", value: "horusInit" },
    { name: "screenTitle", value: "horusInit" },
    { name: "latitude", value: "37.421998333333335" },
    { name: "longitude", value: "-122.08400000000002" },
    { name: "dependenciaOrigem", value: "551-7" },
    { name: "numeroContratoOrigem", value: "246483-7" },
    { name: "deviceModel", value: "Android SDK built for x86" },
    { name: "featureHorusKeepAlive", value: "0" },
  ],
  jwt: "teste",
};

// WebSocket Secure URL - wss:// protocol ensures WebSocket transport
// The mqtt.js library automatically uses WebSocket when wss:// or ws:// is in the URL
const url = `wss://${ROUTE_HOST}${PATH}`;

console.log(`Connecting via WebSocket (wss://) to: ${url}`);
console.log(`Topic: ${TOPIC}`);
console.log(`Transport: WebSocket Secure`);

const client = mqtt.connect(url, {
    protocol: "wss", // WebSocket Secure protocol - ensures WebSocket transport
    protocolVersion: 4, // MQTT 3.1.1
    clientId: `ocp-test-${Math.random().toString(16).slice(2)}`,
    rejectUnauthorized: false, // <-- ignore untrusted certs (TEST ONLY)
    connectTimeout: 10000, // 10 seconds
    reconnectPeriod: 0, // Disable auto-reconnect for testing
    // WebSocket-specific options passed to underlying WebSocket library
    wsOptions: {
      rejectUnauthorized: false, // Ignore certificate validation for OpenShift self-signed certs
    },
  });

client.on("connect", () => {
  console.log("✓ Connected to MQTT broker");

  client.publish(TOPIC, JSON.stringify(payload), { qos: 1 }, (err) => {
    if (err) {
      console.error("✗ Publish error:", err);
      client.end();
      process.exit(1);
    } else {
      console.log(`✓ Published to topic: ${TOPIC}`);
      console.log("Payload:", JSON.stringify(payload, null, 2));
      client.end();
      process.exit(0);
    }
  });
});

client.on("error", (e) => {
  console.error("✗ Connection error:", e.message || e);
  if (e.code) console.error("  Error code:", e.code);
  client.end(true);
  process.exit(1);
});

client.on("close", () => {
  console.log("Connection closed");
});

client.on("offline", () => {
  console.error("✗ Client went offline");
  process.exit(1);
});

client.on("reconnect", () => {
  console.log("Attempting to reconnect...");
});

// Timeout after 15 seconds if no connection
setTimeout(() => {
  if (!client.connected) {
    console.error("\n✗ Connection timeout after 15 seconds");
    console.error("\nTroubleshooting steps:");
    console.error("1. Verify WebSocket is enabled in the pod:");
    console.error("   oc get pod -n kafka -l app.kubernetes.io/name=mqtt-server");
    console.error("   oc exec <pod-name> -n kafka -- env | grep MQTT_BROKER_WS_ENABLED");
    console.error("   (Should show MQTT_BROKER_WS_ENABLED=true)");
    console.error("\n2. Check if Route exists for WebSocket:");
    console.error("   oc get route mqtt-server-ws -n kafka");
    console.error("   If missing, apply: oc apply -f mqtt-server/src/main/k8s/route-ws.yaml");
    console.error("\n3. Check pod logs for WebSocket startup:");
    console.error("   oc logs <pod-name> -n kafka | grep -i websocket");
    console.error("   (Should show 'ws/8090' in the startup message)");
    console.error("\n4. Verify the Route host matches:");
    console.error("   oc get route mqtt-server-ws -n kafka -o jsonpath='{.spec.host}'");
    client.end(true);
    process.exit(1);
  }
}, 15000);

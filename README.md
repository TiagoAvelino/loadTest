# Load Tests Examples with Camel, Kafka and Opentelemetry

Load tests are an essential practice in software development as they allow evaluating the performance and capacity of a system under high-demand conditions. This type of testing simulates situations where many users are interacting simultaneously with the system, performing various operations such as sending requests, processing data, and accessing resources.

The goal of load testing is to identify potential bottlenecks and limitations of the system, ensuring that it can handle the expected load and maintain acceptable performance. During the test, important metrics are collected and analyzed, including response time, throughput, resource utilization, and scalability.

There are various approaches to conducting load tests. One of them is to use specialized tools that allow simulating a large number of concurrent users, generating realistic workloads, and monitoring the system's performance. These tools provide detailed metrics and reports that help identify potential issues.

Furthermore, load tests can be complemented by techniques such as horizontal scaling, where the system is distributed across multiple servers to increase its processing capacity. This allows evaluating how the system behaves when horizontally scaled, handling higher loads and distributing the processing among different nodes.

If you're looking for a simple and quick solution to test Kafka performance, Kafka Perf Test with the CLI can be a viable option. This tool allows simulating production-level production and consumption loads on a Kafka cluster, with customized configurations to adjust message volume, production rate, message size, among other parameters. It is suitable if you need to perform quick tests and do not require complex integration with other tools or APIs.

On the other hand, if you're seeking a more comprehensive and flexible approach, developing an API for production and an application for consumption, combined with the use of a load testing tool like K6 or JMeter, can offer more possibilities. This approach allows creating more realistic test scenarios involving interactions with other parts of the system, data manipulation, specific validations, among other aspects. K6 is a popular tool for load testing and can be easily integrated into your development and CI/CD workflow.

This approach allows simulating the production and consumption behavior of data in an environment closer to the real-world usage scenario. You can create custom test cases, including specific interactions with other parts of the system, data manipulation, integrity validations, and other relevant aspects for your use case. That's why I chose this approach to demonstrate in this repository. The topics below will show how to deploy each tool so that we can eventually run the tests. To get started, you'll need a Red Hat OpenShift cluster and the following three namespaces:

- kafka: for deploying Kafka and Kafka Exporter
- openshift-tempo-operator: for deploying OpenTelemetry and Tempo operators

## Deploy the Open Telemetry

In order to get the data from our application, first is necessary to Install two operators: **Red Hat build of OpenTelemetry** and **Tempo Operator**.

In Openshift operator hub search for Red Hat build of OpenTelemetry and install with default configuration.

![](images/Opentelemetry.png)

After the operator get installed we can proced with the configuration of a new CRD for our collector for this we have to apply the [collector.yaml](opentelemetry/collector.yaml)

![](images/OpenDistributedTracingOperator.png)

```bash
oc new-project openshift-tempo-operator
oc apply -f opentelemetry/collector.yaml -n openshift-tempo-operator
```

### Configure Tempo Operator

To collect and analyze telemetry data from our application, we need to configure the OpenShift Tempo Operator. This operator provides a user interface to visualize the telemetry data collected by the application. To function correctly, the Tempo Operator requires an S3-compatible storage backend.

#### Step 1: Set Up MinIO S3 Storage

First, configure your storage by creating a MinIO S3 bucket. Deploy MinIO by applying the provided [minio.yaml](opentelemetry/minio.yaml) file in the `minio` namespace:

```bash
oc apply -f opentelemetry/minio.yaml -n minio
```

After installation, access the MinIO web interface by retrieving the MinIO UI route in OpenShift. Log in with the following credentials:

- **Username:** `minio`
- **Password:** `minio123`

Create a bucket named `tempo` to store telemetry data.

![MinIO Bucket Creation](images/Bucket.png)

### Step 2: Configure Tempo Operator

With the bucket ready, configure your Tempo stack. First, install the Tempo Operator from the Operator catalog in OpenShift.

![Tempo Operator Configuration](images/Tempo.png)

Then apply the provided [tempo.yaml](opentelemetry/tempo.yaml) file to deploy the Tempo stack with Jaeger interface for data visualization.

```bash
oc apply -f opentelemetry/secret-minio.yaml -n openshift-tempo-operator
oc apply -f opentelemetry/tempo.yaml -n openshift-tempo-operator
```

### Step 3: Verify Tempo Stack

After creating the Tempo stack, verify its operation by accessing the Tempo route provided by OpenShift.

## Deploy Kafka Cluster and Kafka Exporter

Now that we have a functional Opentelemetry, we will deploy our Kafka Cluster with the Kafka Exporter. Change target project to "kafka" project created previously.
In Openshift Operator hub, install AMQ Streams Operator with default configuration like image below:

![](images/AMQStreamsOperator.png)

After, we need create a Kafka Cluster with Kafka Exporter Custom Resource, click on Installed Operators, click on AMQ Streams and in Kafka section, click on "Create Instance"

![](images/KafkaInstance.png)

Change to Yaml view and apply a yaml file like this [kafka-cr.yaml](kafka/kafka-cr.yaml) in folder kafka:

![](images/KafkaYaml.png)

Next, create the Kafka metrics ConfigMap based on the [kafka-metrics-cm.yaml](kafka/kafka-metrics-cm.yaml) file using the following commands:

```bash
cd kafka
oc apply -f kafka-metrics-cm.yaml
```

Now that we have a functional Kafka cluster, let's create the topic for use in our tests. To do this, go back to the 'Installed Operators' section of Openshift and click on the AMQ Streams Operator, then click on the 'Kafka Topic' section and click 'Create Instance'. Apply the YAML as shown below:

![](images/KafkaTopic.png)

## Deploy Prometheus and Grafana Dashboards

Now we need to install Prometheus to gather metrics from Kafka, and we will create a dashboard in Grafana to help us monitor the resource consumption of the Kafka cluster during load testing.

### Install Prometheus

To install Prometheus, let's go back to the Openshift console and in the Operator Hub section, we will install the Prometheus operator below:

![](images/PrometheusOperator.png)

After the operator is installed, we will open a terminal window and navigate to the folder where we mapped this repository, then to the custom resources folder, and finally to the kafka-exporter folder. Log on openshift via command line and we will apply the following commands:

Create a strimzi pod monitor

```bash
oc apply -f strimzi-pod-monitor.yaml
```

Create a rules for prometheus

```bash
oc apply -f prometheus-rules.yaml
```

Create additional rules for prometheus

```bash
oc apply -f prometheus-additional.yaml
```

Finally create a prometheus instance

```bash
oc apply -f prometheus.yaml
```

Wait for the Prometheus pods to be up and running, and we will proceed to the next section.

### Install Grafana and Setup Dashboards

To install Grafana, it's quite simple. We just need to apply the grafana.yaml file located in the grafana directory using the following commands:

```bash
cd grafana
oc apply grafana.yaml
```

After grafana pods are running you need a create route for grafana service. And log on grafana with user 'admin' and 'admin' password. To setup a datasource, create in config and datasources like bellow:

![](images/GrafanaDatasource.png)

Fill in the fields as shown in the image below and then click on the "Save and Test" button.

![](images/GrafanaDatasource2.png)

With the datasource configured, we will proceed with the deployment of the dashboard. To do this, click on the plus symbol on the sidebar of Grafana and then click on "Import" as shown below:

![](images/GrafanaDashboard.png)

We should copy the content of the file [strimzi-kafka-exporter.json](grafana/strimzi-kafka-exporter.json) into the text area and click on "Import" as shown in the image above. Then, select the previously created datasource and click on "Import" as shown below.

![](images/GrafanaDashboardImport.png)

There you have it, the first dashboard is created. If you wish, you can repeat the process for the files [strimzi-kafka.json](grafana/strimzi-kafka.json) and [strimzi-zookeeper.json](grafana/strimzi-zookeeper.json).

# MQTT Connector Application

This repository contains four projects: `mqtt-producer`, `mqtt-server`, `cons-kafka-prod-kafka`, and `cons-kafka-prod-mqtt`. To run the application locally:

1. Run `mqtt-server` first:

```bash
cd mqtt-server
quarkus dev
```

2. Run `mqtt-producer`:

```bash
cd mqtt-producer
quarkus dev
```

3. Run `cons-kafka-prod-kafka`:

```bash
cd cons-kafka-prod-kafka
quarkus dev
```

4. Run `cons-kafka-prod-mqtt`:

```bash
cd cons-kafka-prod-mqtt
quarkus dev
```

### Testing the Application

Send a POST request to the `mqtt/send` endpoint to test the application:

```bash
curl -X POST 'http://localhost/mqtt/send?topic=mqtt-message-in/1/2/app/test' \
-H 'Content-Type: application/json' \
-H 'Accept: */*' \
-d '{"message": "teste1", "jwt": "teste"}'
```

## Deploying to OpenShift

There are two deployment methods:

### Method 1: Automatic Deployment

Add `quarkus.openshift.deploy=true` in `application.properties`. Then:

```bash
oc project <<my-project>>
cd <project-directory>
quarkus build
```

For `mqtt-server`, manually edit the service configuration to include:

```yaml
service:
  - name: mqtt
    protocol: TCP
    port: 1883
    targetPort: 1883
```

### Method 2: Manual Deployment (command to build jar mvn package -Dquarkus.package.type=uber-jar)

Deploy using pre-built images:

```bash
oc apply -f mqtt-server/src/main/k8s/deployment.yaml -n kafka
oc apply -f mqtt-server/src/main/k8s/service.yaml -n kafka

oc apply -f mqtt-producer/src/main/k8s/deployment.yaml -n kafka
oc apply -f mqtt-producer/src/main/k8s/service.yaml -n kafka

oc apply -f cons-kafka-prod-kafka/src/main/k8s/deployment.yaml -n kafka
oc apply -f cons-kafka-prod-kafka/src/main/k8s/service.yaml -n kafka

oc apply -f cons-kafka-prod-mqtt/src/main/k8s/deployment.yaml -n kafka
oc apply -f cons-kafka-prod-mqtt/src/main/k8s/service.yaml -n kafka
```

## K6 tests

K6 is an open-source load testing tool designed for developers and focused on simplicity and scalability. It allows you to write and execute load tests using JavaScript, making it easy to define complex scenarios and simulate realistic user behavior. With K6, you can generate high levels of concurrent virtual users to stress test your system and measure its performance under different load conditions. It provides detailed metrics and real-time results, enabling you to identify bottlenecks, measure response times, and assess the scalability and stability of your application. K6's scripting capabilities, extensibility, and integration with other tools make it a popular choice for load testing in agile development and continuous integration workflows.

To run tests using K6 in OCP, do you need install the following programs in your computer:

- Go
- Kustomize
- Kubectl
- Make

After you need clone the K6 Operator repository with this commands:

```bash
git clone https://github.com/grafana/k6-operator && cd k6-operator
```

To deploy K6 Operator, you need run the following command:

```bash
make deploy
```

Now, we back to this repository folder and go create a configmap with our K6 test plan:

```bash
oc project k6-operator-system
cd k6
oc create configmap k6-api-test --from-file load-tests.js
```

Finally, we do create a k6 instance with this command:

```bash
oc apply -f k6-sample.yaml
```

You can follow the Test run with command "oc get pods" and observe, in this case 4 pods simultaneously running tests like bellow:

![](images/K6Sample.png)

Ajusts the test case for what your need and enjoy!

## Conclusions

Despite using simpler approaches and spending a lot of time deploying metrics tools for load testing, this approach allows us to tune our environment as we execute the tests. We can adjust the number of users to a scenario that truly makes sense for the application, thus obtaining more accuracy in tuning the environment.

## References

[Open Telemetry Deployment Documentation](https://docs.openshift.com/container-platform/4.12/distr_tracing/distr_tracing_install/distr-tracing-deploying-otel.html)

[Red Hat AMQ Streams Deployment Documentation](https://access.redhat.com/documentation/pt-br/red_hat_amq/7.7/html-single/deploying_and_upgrading_amq_streams_on_openshift/index#deploy-tasks_str)

[Red Hat AMQ Streams Assembly Metrics](https://access.redhat.com/documentation/pt-br/red_hat_amq/7.7/html-single/deploying_and_upgrading_amq_streams_on_openshift/index#assembly-metrics-str)

[Running Distributed Tests on Kubernetes with K6](https://k6.io/blog/running-distributed-tests-on-k8s/)

https://docs.openshift.com/container-platform/4.16/nodes/cma/nodes-cma-autoscaling-custom-trigger.html

<!-- Configuring the custom metrics autoscaler to use OpenShift Container Platform monitoring

oc create secret generic kafka-auth --from-literal username=redhat-user --from-literal password=redhat123
oc get secret my-cluster-cluster-ca-cert -n kafka -o jsonpath='{.data.ca\.crt}' | base64 -d > ca.crt
oc create secret generic kafka-client-ssl-secret --from-file=truststore.jks --from-literal truststore-password=redhat
keytool -import -file ca.crt -alias ca -keystore truststore.jks -storepass redhat -noprompt
You must perform the following tasks, as described in this section:

Create a service account.

oc create serviceaccount thanos

Create a secret that generates a token for the service account.

$ oc create -f secret-token.yaml

$ oc describe serviceaccount thanos

Create the trigger authentication.

$ oc create -f custom-metrics-autoscaler/trigger-auth.yaml

Create a role.

$ oc create -f custom-metrics-autoscaler/role.yaml

$ oc create -f custom-metrics-autoscaler/role-binding.yaml

Add that role to the service account.

Reference the token in the trigger authentication object used by Prometheus.

falta verificar mecanismo de authenticação não está correto**_++++_**+++**+**+_+_+**+_+_+_+_+\_+**+_+_+_+_+_+_+\_+ -->

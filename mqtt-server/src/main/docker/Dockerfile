# Containerfile (uber-JAR)
FROM registry.redhat.io/ubi9/openjdk-21-runtime@sha256:9414aacc4fca4c7e55df94c58f66f6f91b9fd0ed88f7110642c815db92172819

# OpenShift will assign an arbitrary UID; this base image
# already includes a non-root user with proper permissions.
USER 1001

WORKDIR /work

# Copy the Quarkus “runner” JAR built by Maven/Gradle
COPY target/mqtt-server-1.0.0-SNAPSHOT-runner.jar app.jar

# Quarkus listens on 8080 by default; bind to all interfaces
EXPOSE 8080 1883 8090
# Launch the JAR
CMD ["java","-jar","app.jar"]



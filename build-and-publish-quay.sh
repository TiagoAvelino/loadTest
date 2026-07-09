#!/usr/bin/env bash

set -e  # Exit on error

# Get version from command line argument or environment variable
VERSION="${1:-${VERSION}}"

if [ -z "$VERSION" ]; then
    echo "Error: Version is required"
    echo "Usage: $0 <version>"
    echo "   or: VERSION=<version> $0"
    exit 1
fi

echo "Building and publishing all microservices with version: $VERSION"
echo ""

# Base directory (where the script is located)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QUAY_REPO="quay.io/rh_ee_tavelino"

cd "${SCRIPT_DIR}/"tracing-bridge""
mvn clean package -Dquarkus.package.type=uber-jar -DskipTests

deploy_component() {
    local component="$1"
    local version="$2"
    local image_tag="${QUAY_REPO}/${component}:${version}"
    
    echo "=========================================="
    echo "Processing: $component"
    echo "Image tag: $image_tag"
    echo "=========================================="
    
    # Change to component directory
    cd "${SCRIPT_DIR}/${component}" || {
        echo "Error: Directory ${component} not found"
        return 1
    }
    
    # Step 1: Build with Maven
    echo "Step 1: Building with Maven..."
    mvn clean package -Dquarkus.package.type=uber-jar -DskipTests || {
        echo "Error: Maven build failed for $component"
        return 1
    }
    
    # Step 2: Build container image
    echo "Step 2: Building container image..."
    podman build -t "$image_tag" -f src/main/docker/Dockerfile . || {
        echo "Error: Podman build failed for $component"
        return 1
    }
    
    # Step 3: Push image
    echo "Step 3: Pushing image to Quay.io..."
    podman push "$image_tag" || {
        echo "Error: Podman push failed for $component"
        return 1
    }
    
    echo "✓ Successfully deployed $component"
    echo ""
}

# Process each microservice
for comp in mqtt-producer mqtt-server cons-kafka-prod-kafka cons-kafka-prod-mqtt; do
    deploy_component "$comp" "$VERSION"
done

echo "========================='================="
echo "All microservices have been built and published!"
echo "Version: $VERSION"
echo "=========================================="

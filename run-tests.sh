#!/bin/bash
set -e
for d in */ ; do
    if [ -f "$d/pom.xml" ]; then
        echo "Running tests in $d"
        (cd "$d" && mvn test)
    fi
done
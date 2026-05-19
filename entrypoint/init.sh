#!/bin/bash

# This script is needed so the main Java command doesn't get PID 1 and therefore
# it can be killed on a negative healthcheck.

# Ensure curl is available for the healthcheck. The eclipse/rdf4j-workbench
# image (>=5.3.1-tomcat) ships without curl, which makes the healthcheck's
# `curl ... || (pkill java)` branch fire on every probe and kills Tomcat
# mid-deploy. Requires the container to start as root (see docker-compose.yml).
if ! command -v curl >/dev/null 2>&1; then
    apt-get update && apt-get install -y --no-install-recommends curl
fi

# Drop privileges back to the image's default tomcat user for the JVM itself.
runuser -u tomcat -- catalina.sh run

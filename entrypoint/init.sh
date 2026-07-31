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

# Clear the "has been ready" marker for this container instance. The healthcheck
# only restarts Tomcat when /var/info/ready exists, so that a probe failing while
# the WARs are still deploying doesn't kill a perfectly healthy start-up. But
# /var/info is a host-mounted volume, so the marker outlived the container it was
# created for: after any `docker compose up -d rdf4j` it was already present, and
# the guard protected only a genuinely first-ever start.
#
# Observed on kpxl 2026-07-31: the container was recreated at 14:40:50, the first
# probe failed at 14:40:55 while Tomcat was still deploying, and the marker from
# the previous instance made the healthcheck call restart_tomcat — a kill during
# a WAR deploy, which is exactly the condition implicated in the acknowledged-
# write loss of issue #142. Only RDF4J_HEALTHCHECK_RESTART=off stopped it.
#
# Removing it here makes the marker mean what its use claims: "*this* container
# instance has been seen healthy". The first successful probe recreates it.
rm -f /var/info/ready

# Drop privileges back to the image's default tomcat user for the JVM itself.
runuser -u tomcat -- catalina.sh run

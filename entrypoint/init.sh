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

# Align host-volume ownership with the image's tomcat user: the 6.0.0-tomcat
# image runs tomcat as uid 100 where 5.3.x used 101, so data, logs, and heap
# dumps written under the old image (or by root) are otherwise unwritable
# after an image bump. We are still root here; the file count is small enough
# that this is fast even on a fully ingested store.
chown -R tomcat: /var/rdf4j /usr/local/tomcat/logs /var/info

# Raise Tomcat's HTTP connector thread pool. The shipped server.xml leaves the
# 8080 connector without a maxThreads attribute, so Tomcat's default of 200
# applies (the maxThreads="150" visible in server.xml is inside a commented-out
# <Executor> block and is NOT in effect).
#
# Why this is needed: SERVICE clauses in /api queries loop back to this same
# server, so every outer federated query needs a SECOND worker thread to serve
# its own sub-request. The worker pool must therefore stay at or above twice
# maxConnPerRoute, or the connection-pool ceiling set in docker-compose.yml
# cannot actually be reached.
#
# Note this is NOT itself the wedge fix. A dump taken while genuinely wedged
# (2026-08-20) had only 42 http-nio workers against maxThreads=400 while all 60
# route connections were held -- connections bind, threads do not, which is why
# raising maxThreads alone measurably changed nothing. It is here so that
# raising maxConnPerRoute is safe. See the rationale block in docker-compose.yml.
#
# conf/ is not a mounted volume, so this is re-applied cleanly on every start.
if [ -n "$RDF4J_TOMCAT_MAX_THREADS" ]; then
    sed -i "s|<Connector port=\"8080\" protocol=\"HTTP/1.1\"|<Connector port=\"8080\" protocol=\"HTTP/1.1\" maxThreads=\"$RDF4J_TOMCAT_MAX_THREADS\"|" \
        /usr/local/tomcat/conf/server.xml
    echo "init.sh: set Tomcat connector maxThreads=$RDF4J_TOMCAT_MAX_THREADS"
    grep -n 'Connector port="8080"' /usr/local/tomcat/conf/server.xml
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
#
# Run it in the background and wait, with SIGTERM/SIGINT forwarded to the JVM.
# Without this, `docker stop` / `docker compose restart` / a container exit hands
# SIGTERM to *this* script (PID 1); bash sitting in a foreground child does not
# pass it on, the JVM never runs its shutdown hook, and 10 s later Docker sends
# SIGKILL. Every Docker-initiated restart was therefore a hard kill — and a hard
# kill of a store mid-write is the condition implicated in the acknowledged-write
# loss of issue #142. kpxl restarted this container 97 times in the four days to
# 2026-08-05, so this was the normal case, not an edge case.
#
# Deliberately still not `exec`: the JVM must stay a child rather than become PID 1,
# both because signals to PID 1 are ignored unless explicitly handled and because
# the healthcheck's restart_tomcat reaches it with pkill (see docker-compose.yml).
#
# The JVM is signalled directly rather than via $! (which is runuser's PID, not
# the JVM's). Verified 2026-08-05: `kill -TERM` on the runuser PID does NOT reach
# the JVM, so forwarding via $! would silently do nothing. `pkill java` is the
# mechanism the healthcheck's restart_tomcat already relies on in this image.
#
# The wait is bounded rather than a bare `wait`: if pkill is ever missing or
# matches nothing, blocking here would swallow the signal and sit until Docker's
# SIGKILL — guaranteeing the hard kill this handler exists to prevent. Bounded at
# just under the stop_grace_period set in docker-compose.yml.
term_handler() {
    if command -v pkill > /dev/null 2>&1; then
        pkill -TERM java 2>/dev/null
    else
        kill -TERM "$child_pid" 2>/dev/null
    fi
    i=0
    while [ "$i" -lt 110 ] && kill -0 "$child_pid" 2>/dev/null; do
        sleep 1
        i=$((i + 1))
    done
    exit 143
}
trap term_handler TERM INT

runuser -u tomcat -- catalina.sh run &
child_pid=$!
# A trapped signal interrupts `wait`, but term_handler exits from inside the trap,
# so control only reaches the next line when the JVM exited on its own — e.g. via
# -XX:+ExitOnOutOfMemoryError. Propagating that status exits the container, which
# is what lets `restart: unless-stopped` recover it.
wait "$child_pid"
exit $?

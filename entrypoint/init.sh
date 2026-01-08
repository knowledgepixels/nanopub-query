#!/bin/bash

# This script is needed so the main Java command doesn't get PID 1 and therefore
# it can be killed on a negative healthcheck.

# Install curl if not already installed (needed for healthcheck)
if ! command -v curl &> /dev/null; then
    apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
fi

catalina.sh run

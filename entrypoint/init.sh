#!/bin/bash

# This script is needed to the main Java command doesn't get PID 1 and therefore
# it can be killed on a negative healthcheck.

catalina.sh run

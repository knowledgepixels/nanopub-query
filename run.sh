#!/usr/bin/env bash

cd "$( dirname "${BASH_SOURCE[0]}" )"

set -e

docker compose down
./mvnw package -Dmaven.test.skip=true
docker build -f local.Dockerfile -t nanopub/query:latest .
docker compose up

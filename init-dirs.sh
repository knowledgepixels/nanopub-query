#!/usr/bin/env bash

cd "$(dirname "$0")"

mkdir -p load
mkdir -p data/rdf4j/data
mkdir -p data/rdf4j/logs

sudo chmod -R 777 data

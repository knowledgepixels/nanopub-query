#!/usr/bin/env bash

cd "$(dirname "$0")"
cd ..

echo "Retrieving nanopubs..."
rm -f load/all-signed-nanopubs.trig
cat load/all-signed-nanopub-uris.txt \
  | sed 's_http://purl.org/np/_http://localhost:8080/_' \
  | awk '{ count++; print "echo "count"; np get "$1" >> load/all-signed-nanopubs.trig"; }' \
  | bash

#!/usr/bin/env bash

cd "$(dirname "$0")"
cd ..

mkdir -p load
rm -f load/all-signed-nanopub-uris.txt

for PAGE in {1..38}; do
  echo "Downloading page $PAGE"
  curl -s -H "Accept: text/csv" "https://grlc.nps.petapico.org/api/local/local/find_signed_nanopubs?page=$PAGE" \
    | sed 1d \
    | awk -F, '{print $1}' \
    | sed 's/"//g' \
    >> load/all-signed-nanopub-uris.txt
done

echo "Counting downloaded nanopubs:"
cat load/all-signed-nanopub-uris.txt | wc -l

echo "Counting unique nanopubs (should be the same as above):"
cat load/all-signed-nanopub-uris.txt | sort | uniq | wc -l

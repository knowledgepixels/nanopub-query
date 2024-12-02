#!/bin/sh

cd /app/load

echo "Autofetch init"

rm -f nanopubs-autofetch.temp.txt

URLPREFIX=https://grlc.nps.knowledgepixels.com/api/local/local/find_signed_nanopubs?page=
for PAGE in $(seq 100); do
  echo "Loading page $PAGE..."
  wget -q -O - --header="Accept: text/csv" "$URLPREFIX$PAGE" \
    | sed 1d | awk -F, '{print $1}' | sed 's/"//g' \
    >> nanopubs-autofetch.temp.txt
done

# Sometimes pubkey (wrongly) have newlines in them, and then the above script returns
# extra lines that we exclude here:
cat nanopubs-autofetch.temp.txt | grep -E '^https?://' > nanopubs-autofetch.temp2.txt
rm nanopubs-autofetch.temp.txt
mv nanopubs-autofetch.temp2.txt nanopubs-autofetch.txt

echo "Autofetch done"

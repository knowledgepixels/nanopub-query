#!/usr/bin/env bash
#
# Compares the per-repo nanopub count and checksum across query instances,
# for the meta and full repos of each. Two invariants must hold on a healthy,
# caught-up fleet:
#
#   1. Within one instance, meta and full report the SAME count and checksum
#      (full behind meta = lost full-repo commits, the ShardReconciler's
#      driver-repo blind spot — see the 2026-08-11 incident, where full was
#      silently short 106 nanopubs on one instance and 5 on another).
#   2. Across instances, the numbers match (modulo ingestion lag of a few
#      seconds; re-run before reading a small diff as a problem).
#
# Run it from anywhere (queries the public endpoints):
#
#   ./check-fleet-consistency.sh
#   INSTANCES="https://query.example.org" ./check-fleet-consistency.sh
#
# Exit code is 0 when every count+checksum pair agrees, 1 otherwise.

set -euo pipefail

INSTANCES=${INSTANCES:-"https://query.knowledgepixels.com https://query.petapico.org https://query.nanodash.net"}
REPOS=${REPOS:-"meta full"}

QUERY='PREFIX npa: <http://purl.org/nanopub/admin/> SELECT ?p ?o WHERE { GRAPH npa:graph { npa:thisRepo ?p ?o } }'

declare -A results
consistent=true
reference=""

printf '%-45s %-6s %-9s %s\n' "INSTANCE" "REPO" "COUNT" "CHECKSUM"
for instance in $INSTANCES; do
    for repo in $REPOS; do
        line=$(curl -fsS -m 30 -H "Accept: text/csv" --data-urlencode "query=$QUERY" \
                    "$instance/repo/$repo" 2>/dev/null \
                   | tr -d '\r' \
                   | awk -F, '/hasNanopubCount/{c=$2} /hasNanopubChecksum/{s=$2} END{print c" "s}') \
            || line="UNREACHABLE -"
        [ -n "$line" ] || line="NO-DATA -"
        printf '%-45s %-6s %-9s %s\n' "$instance" "$repo" ${line}
        if [ -z "$reference" ]; then
            reference="$line"
        elif [ "$line" != "$reference" ]; then
            consistent=false
        fi
    done
done

if $consistent; then
    echo "OK: all instances and repos agree."
else
    echo "MISMATCH: at least one repo diverges — a stable full-behind-meta gap on one"
    echo "instance means lost full-repo commits; find the missing nanopubs by diffing"
    echo "'SELECT ?np WHERE { GRAPH npa:graph { ?np npa:artifactCode ?c } }' between its"
    echo "meta and full repos, and re-load them (or FORCE_RESYNC)."
    exit 1
fi

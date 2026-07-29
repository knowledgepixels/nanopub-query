#!/usr/bin/env bash
#
# Sets the shard-reconciliation checkpoint (see ShardReconciler, issue #139/#142).
#
# The periodic sweep only verifies nanopubs with a driver-repo load number ABOVE
# the persisted checkpoint. Lowering the checkpoint makes the next tick (within
# 5 minutes) re-verify and repair everything above it. Use this to heal shard
# losses that happened before the reconciler was deployed:
#
#   ./set-reconciliation-checkpoint.sh 86293     # re-verify load numbers > 86293
#   ./set-reconciliation-checkpoint.sh -1        # full-history integrity sweep
#                                                # (~1000 nanopubs per 5-min tick)
#
# Run this ON THE SERVER, against the backend RDF4J server (not through nginx).
# The endpoint defaults to the docker-compose port mapping; override via the
# second argument or the ENDPOINT_BASE environment variable:
#
#   ./set-reconciliation-checkpoint.sh 86293 http://localhost:8081/rdf4j-server/
#
# IMPORTANT: deploy a release containing the reconciler first and wait for its
# first tick ("Shard reconciliation checkpoint initialized ..." in the logs).
# This script refuses to run before that, because the reconciler would
# re-initialize the checkpoint to the current maximum and undo the change.

set -euo pipefail

NPA="http://purl.org/nanopub/admin/"

if [ $# -lt 1 ]; then
  echo "Usage: $0 <load-number> [endpoint-base]" >&2
  exit 1
fi

CHECKPOINT="$1"
if ! [[ "$CHECKPOINT" =~ ^-?[0-9]+$ ]] || [ "$CHECKPOINT" -lt -1 ]; then
  echo "Error: <load-number> must be an integer >= -1, got: $CHECKPOINT" >&2
  exit 1
fi

ENDPOINT="${2:-${ENDPOINT_BASE:-http://localhost:8081/rdf4j-server/}}"
[[ "$ENDPOINT" == */ ]] || ENDPOINT="$ENDPOINT/"
ADMIN_REPO="${ENDPOINT}repositories/admin"

status=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$ADMIN_REPO/size" || true)
case "$status" in
  200) ;;
  000) echo "Error: cannot reach $ADMIN_REPO — is the RDF4J backend running?" >&2; exit 1 ;;
  *)   echo "Error: admin repo not available at $ADMIN_REPO (HTTP $status)." >&2; exit 1 ;;
esac

read_admin_value() {
  curl -sf -H "Accept: text/csv" --data-urlencode "query=
    SELECT ?o WHERE { GRAPH <${NPA}graph> {
      <${NPA}thisRepo> <${NPA}$1> ?o
    } }" "$ADMIN_REPO" | tail -n +2 | tr -d '\r'
}

driver=$(read_admin_value hasReconciliationDriver)
current=$(read_admin_value hasReconciliationCheckpoint)

if [ -z "$driver" ] || [ -z "$current" ]; then
  echo "Error: no reconciliation checkpoint found in the admin repo at $ADMIN_REPO." >&2
  echo "Deploy a release with the shard reconciler and wait for its first tick" >&2
  echo "(logs: \"Shard reconciliation checkpoint initialized ...\"), then re-run." >&2
  exit 1
fi

echo "Current checkpoint: $current (driver repo: $driver)"
echo "Setting checkpoint: $CHECKPOINT"

curl -sf -X POST -H "Content-Type: application/sparql-update" --data-binary "
  PREFIX npa: <${NPA}>
  PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
  DELETE WHERE { GRAPH npa:graph { npa:thisRepo npa:hasReconciliationCheckpoint ?c } };
  INSERT DATA { GRAPH npa:graph {
    npa:thisRepo npa:hasReconciliationCheckpoint \"$CHECKPOINT\"^^xsd:long
  } }" "$ADMIN_REPO/statements"

echo "Checkpoint now:     $(read_admin_value hasReconciliationCheckpoint)"
echo "The next reconciliation tick (within 5 minutes) will sweep load numbers > $CHECKPOINT."

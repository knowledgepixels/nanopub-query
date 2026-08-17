# RDF4J 6.0.0 migration — pre-migration baseline

Captured **2026-08-17T14:13Z**, immediately before the first host is migrated.

## Why this baseline is usable

The fleet has been running **rdf4j 5.3.2 on stores wiped and re-ingested on
2026-08-12**. That makes it a like-for-like control for a 6.0.0 run on freshly
wiped stores: same client (nanopub-query 1.25.0, SERIALIZABLE already removed
from all writers), same data, same config, same hosts — only the server version
differs. Store age/fragmentation, the usual confound, is matched.

Note this window was previously mis-recorded as "6.0.0". It was not; see
`Server version number: 9.0.119.0` (Tomcat 9 = `5.3.2-tomcat`) in the container
log at the 08-12 deploy, and `docker inspect rdf4j` on nanodash.net.

## Baseline numbers — kpxl (host A), 5 days to 2026-08-17T14:13Z

Source: Loki, `{service_name="fluent-bit"}`. Loki collects from **kpxl only**.

| marker | count / 5d | per day |
|---|---|---|
| `Aborted (core dumped)` | 97 | **19.4** |
| `malloc_consolidate` | 55 | 11.0 |
| `Server startup in` (Docker restart after each abort) | 100 | 20.0 |

Aborts exceed `malloc_consolidate` because a substantial fraction abort with no
glibc line at all — consistent with what was reported upstream.

**Counting trap:** Loki's own container logs the text of every query into the
same `fluent-bit` stream, so a `|= "core dumped"` count matches your own
searches and inflates the result (measured 363 vs the true 97 in this very
window). Always append `!= "caller=" != "org_id=fake" != "loki"`.

## Per-host restart counts

```
docker inspect --format '{{.RestartCount}}  started={{.State.StartedAt}}' rdf4j
```

`RestartCount` resets to 0 when the container is **recreated** and increments on
each Docker auto-restart, so it counts crashes since the last recreation — read
it together with the container's start time, not on its own.

| host | captured | RestartCount | since | rate |
|---|---|---|---|---|
| A — kpxl | *not captured* | | | |
| B — petapico | *not captured* | | | |
| C — nanodash | 2026-08-17T14:20Z | 2 | recreated 13:26Z (manual restart) | 2 in ~50 min ≈ 2.4/h |

Host C's ~2.4/h is roughly its historical rate (~4/h around 08-13) and well above
kpxl's 19.4/day (0.8/h). One 50-minute sample, so read it as confirmation that
the loop is live on 5.3.2, not as a precise rate.

Deployment layout, confirmed on host C: the infra repo is checked out per host
at `/opt/kpxl/nanopub-infrastructure` (that path name is used on every host, so
it does **not** indicate which host you are on — check the hostname), the stack
runs from its `nanopub-query/` subdirectory, and the checkout **tracks master**.
So master is what the fleet pulls, and the image tag must stay overridable
per host rather than hard-coded, or one pull migrates everything at once.

Host C (nanodash.net) is the important one — it was the crash-looper at roughly
4/hour, so it reaches a verdict fastest and is the recommended first migration.
Hosts B (petapico) and A (kpxl) are not covered by Loki and by Loki respectively,
so restart counts are the only fleet-wide measure.

## Verdict criteria after migrating host C

Run 6.0.0 on host C for >=24h while A and B stay on 5.3.2 as live controls.

- Host C aborts drop to ~0 while A/B keep crashing at baseline → 6.0.0 fixes it.
- Host C stays near its own baseline rate → 6.0.0 does not fix it, and that can
  be reported upstream on eclipse-rdf4j/rdf4j#5970 with evidence this time.

Confirm which version is actually running before trusting any measurement:

```
docker exec rdf4j /usr/local/tomcat/bin/version.sh | grep "Server number"
#  9.0.119.0 = 5.3.2-tomcat      11.0.24.0 = 6.0.0-tomcat
```

Secondary outcome to watch: the federated-connection wedge. On 5.3.2 the pool is
Apache HttpClient 4; 6.0.0 routes federation through a new SPI (JDK or Apache
HC5), so the wedge may change character or disappear. Probe daily:

```
curl -s --data-urlencode 'query=select ?s where { service <http://query:9393/repo/meta> { select ?s where { ?s ?p ?o } limit 1 } }' https://<host>/repo/full
```

Healthy is ~0.15 s; wedged is a 10.1 s timeout.

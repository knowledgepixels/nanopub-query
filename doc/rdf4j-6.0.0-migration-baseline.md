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
| A — kpxl (5.3.2, control) | 2026-08-17T16:41Z | 0 | manual restart 16:12:06Z | no crashes in 29 min |
| B — petapico (5.3.2, control) | 2026-08-17T16:40Z | 2 | recreated ~13:26Z | 2 in ~3.2 h ≈ 15/day |
| C — nanodash (5.3.2, pre-migration) | 2026-08-17T14:20Z | 2 | recreated 13:26Z | 2 in ~50 min ≈ 2.4/h |
| **C — nanodash (6.0.0)** | **2026-08-17T16:10Z** | **0** | **recreated 16:03:58Z** | **← t0, clock starts** |

Both controls are actively crashing on 5.3.2, which is what makes a quiet 24 h on
nanodash interpretable. Note petapico was previously the "clean" host (0 crashes
in the 08-12 tally) — it is not clean any more.

### Interim result, 2026-08-17T18:05Z

| host | version | window | crashes | rate |
|---|---|---|---|---|
| **nanodash** | **6.0.0** (t0 15:19:07Z, confirmed by `Server number: 11.0.24.0`) | 2.8 h | **0** | **0/day** |
| kpxl | 5.3.2 | 1.9 h | 4 | 50.8/day |
| petapico | 5.3.2 | 4.7 h | 3 | 15.5/day |

Controls: 7 crashes in 6.6 host-hours (~1.1/h). Against nanodash's own
pre-migration rate of ~2.4–4/h, zero crashes in 2.8 h has probability ~0.1% if
nothing had changed. Early but pointing one way.

### Measurement method for the 24 h read — use deltas, not absolutes

`RestartCount` is reset by a container **recreation**, so an absolute count is only
meaningful together with `StartedAt`. Two hosts also share the hostname
`Ubuntu-2404-noble-amd64-base`, so the shell prompt does not identify the machine —
one reading was misattributed that way already. Therefore:

1. Always capture `RestartCount` **and** `StartedAt` in the same command.
2. Compare against the 18:05Z anchors below, and if `StartedAt` has moved, the
   counter was reset — measure from the new start instead of differencing.
3. Identify the migrated host by version, never by prompt or by memory.

Anchors at 2026-08-17T18:05Z — nanodash 0 (since 15:19:07Z), kpxl 4 (since
17:42:48Z), petapico 3 (since 16:43:44Z).

## Federation-wedge observations (secondary outcome)

Probe: `select ?s where { service <http://query:9393/repo/meta> { select ?s where
{ ?s ?p ?o } limit 1 } }` against `/repo/full`. Healthy ~0.15 s; wedged = 10.1 s.

| time | kpxl (5.3.2) | petapico (5.3.2) | nanodash |
|---|---|---|---|
| 13:15Z | wedged | wedged | wedged (5.3.2) |
| 16:07Z | **wedged** (started 15:51:29Z, so ≤16 min after a clean start) | ok | ok (6.0.0) |
| 16:41Z | ok (29 min after 16:12 restart) | ok | ok (6.0.0) |

So the wedge recurs on the order of tens of minutes to hours, **not** days as first
thought — but the interval is variable and load-dependent. One host wedging while
another does not, on the same version, means single observations prove little;
judge this outcome on a day of samples, not on any one probe.

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

---

# RESULT — 2026-08-18T06:05Z: 6.0.0 stops the crash loop

**nanodash on 6.0.0: 0 crash-restarts in 14.7 h.
Controls on 5.3.2 over the same night: 51 crashes.**

| host | version | window | crashes | rate |
|---|---|---|---|---|
| **nanodash** | **6.0.0** | 14.7 h (from 15:19:07Z) | **0** | **0/day** |
| kpxl | 5.3.2 | 11.9 h | 17 | 34/day |
| petapico | 5.3.2 | 11.9 h | 34 | 68/day |

Under nanodash's own pre-migration rate (~2.4–4/h) the expected count for this
window was 35–59. Observed zero. P(0) ranges from 4e-16 to 3e-26 depending on
which comparison rate is used; against the controls' contemporaneous rates it is
7e-10 to 6e-19. Both controls ran *above* their 19.4/day baseline that night, so
this is not a quiet period across the fleet.

### How the window was recovered

nanodash's `RestartCount` read 0 at 06:00Z, but `StartedAt` had moved to
04:36:57Z — the container was **recreated**, which resets the counter, so the
count alone proved nothing about the preceding 13.3 h. Tomcat's logs are on a
host mount (`./data/rdf4j/logs`) and survive recreation, and every restart writes
a `Server startup in` line:

```
grep -c "Server startup in" catalina.2026-08-17.log   -> 1   (15:19 migration boot)
grep -c "Server startup in" catalina.2026-08-18.log   -> 1   (04:37:05, the recreation)
```

Two startups total across both files ⇒ no crash-restarts in the window. **Prefer
this method over `RestartCount` for any future comparison** — it is immune to
recreation and gives timestamps. Note the log file timestamps inside are UTC while
`ls` mtimes are local (CEST, UTC+2).

### Caveats

- One host, one 14.7 h window. The 5.3.2 timeline did contain a ~32 h quiet gap
  (08-14 11Z → 08-15 19Z), so quiet stretches on 5.3.2 are not unheard of. What
  makes this different is that it is *contemporaneous*: the controls were crashing
  hard during exactly the same hours.
- Fresh-store confound is largely handled — nanodash was crash-looping at ~4/h
  within hours of its previous fresh re-ingest on 08-12, on 5.3.2.
- The 04:36:57Z recreation is unexplained; worth knowing what caused it.
- Secondary outcome (federation wedge) is **not** resolved by this window: at
  06:01Z all three hosts, both versions, answered the SERVICE probe normally.
  Needs its own observation period.

---

# CONFIRMED — 2026-08-18T14:30Z, full 23h with two 6.0.0 hosts

| host | version | window | crashes | rate |
|---|---|---|---|---|
| **nanodash** | **6.0.0** | 23.2 h | **0** | **0/day** |
| **petapico** | **6.0.0** | 7.7 h | **0** | **0/day** |
| kpxl | 5.3.2 | 23.2 h | **32** | 33.1/day |

Expected for nanodash under its own pre-migration rate (2.4/h) was 56 crashes;
under kpxl's contemporaneous rate, 32. Observed zero. P(0) = 7e-25 and 1e-14.
Two independent hosts on 6.0.0, zero crashes between them, against a control
crashing 33/day over exactly the same hours.

Counted from Tomcat startup lines, with every startup accounted for as a
deliberate operation:

- nanodash: 15:19 (migration), 04:37 (recreation, cause unexplained), 12:54 (restart)
- petapico: 06:47 (post-wipe sync boot), 12:55 (restart)
- `RestartCount=0` on both — a crash would auto-restart and increment it

**Conclusion: rdf4j 6.0.0 fixes the glibc malloc corruption crash loop
(eclipse-rdf4j/rdf4j#5970 defect 3).** The 5.3.2 store wipe is not the cause —
nanodash was crash-looping at ~4/h within hours of its previous fresh re-ingest
on 5.3.2.

**The federation wedge is a separate, unfixed bug.** It wedges on 6.0.0 too, and
was isolated on 2026-08-18 to leaked leases inside rdf4j's own client pool: on
kpxl the loopback route answered in 0.011 s via curl from inside the rdf4j
container, while the same SERVICE query through rdf4j failed at 10.003 s with
`Timeout waiting for connection from pool` (= `connectionRequestTimeout`). At
~45k /api req/h with sub-second hops, real concurrency is 1–2, so 60 held leases
are leaked, not saturated. Version-independent because 6.0.0's JDK/HC5 client
honours the same `maxConnPerRoute=60`. Do not read it as a 6.0.0 regression.

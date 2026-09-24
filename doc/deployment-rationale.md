# Deployment Rationale

Why [docker-compose.yml](../docker-compose.yml) is configured the way it is. Most of these settings were added after a
specific incident, and several look like they could be "simplified" in a way that brings that incident back. Read the
relevant section here before changing one of them.

For how to run an instance, see the [README](../README.md#running-an-instance) and the [operations guide](operations.md).

The fleet instances are deployed from `nanopub-infrastructure/nanopub-query/`, which is the deploy source of truth.
Keep settings in sync with it.

## Ports bound to localhost

Both published ports that carry data are bound to `127.0.0.1`:

- **RDF4J (`127.0.0.1:8081`)**: RDF4J has no authentication, and every repository it serves is writable, including via
  DELETE on the repository URL and via the Workbench UI. Published on all interfaces it is an unauthenticated write
  endpoint on the public IP, which bypasses whatever the reverse proxy restricts. It is reached as
  `http://rdf4j:8080/` from the query container. (Compare the 2026-08-02 Solr wipe on vodex.petapico.org.)
- **Query app (`127.0.0.1:9393`)**: the app has no authentication either, and forwards `/repo` requests of every method
  to RDF4J, so publishing it on all interfaces opens the same hole.

A host firewall does not help here, since Docker manages its own iptables rules. The metrics port `9394` is
localhost-only as well; see [monitoring](../monitoring/README.md).

## RDF4J version

The default is `nanopub/rdf4j-workbench:6.0.0-lmdbpool4`: rdf4j 6.0.0 with the LMDB sail jar replaced by a build of
eclipse-rdf4j/rdf4j#5974 cherry-picked onto the 6.0.0 tag (`8142e783` pooled read transactions, `f493d19` the #6022
`nextId` fix), so every other component is the released 6.0.0. Neither official release is safe for this workload:

- **Stock 6.0.0 corrupts the store.** After a reopen, `nextId` starts too low, so values written after a restart reuse
  IDs that are still in use, and unrelated records end up sharing values (eclipse-rdf4j/rdf4j#6022; eleven corruption
  events in August and September 2026).
- **6.1.0 regressed query planning.** It contains both fixes, but on 2026-09-24 the first space admin grant it
  processed made the `wouldInvalidate` ASK queries of `AuthorityResolver`'s incremental cycle run into their timeout
  on every instance at once. The cycle retries every tick, so RDF4J sat at ~1,100% CPU and the whole fleet, Nanodash
  included, stopped answering. The same queries had run fine on 6.0.0-based builds for 31 earlier admin grants, and
  rolling back to lmdbpool4 cleared it immediately. The likely cause is 6.1.0's rewritten LMDB cardinality estimation.

Known limitation of lmdbpool4: it predates 6.1.0's reserved reader slot, and under a heavy burst it can deadlock
(RDF4J idle at near-zero CPU while every request times out, including the health check; restarting RDF4J clears it).
This was seen once, on a single instance that was carrying the whole fleet's traffic. Move to an official release once
one handles this workload.

The store format is the same in 6.0.0, lmdbpool4 and 6.1.0, so switching between them needs no wipe.

Coming from 5.x is not a plain image swap: 6.x changed the LMDB value-ID layout and refuses to open 5.x stores
("Directory contains data from an older unsupported version of LmdbStore"). Stop the stack, wipe `./data/rdf4j/data`,
and let the query app re-ingest everything from the Registry (a fresh admin repo makes the loader bootstrap from
counter 0). The 6.x image also changed the tomcat uid (101 -> 100); `init.sh` re-chowns the mounted volumes at
startup. The 5.3.x client library in the query app stays compatible (REST protocol 12).

The RDF4J container starts as root so that `init.sh` can install curl (missing from the image since 5.3.1-tomcat, but
required by the healthcheck) and re-chown the volumes. `init.sh` drops to the tomcat user before launching the JVM.

## Query timeout

`RDF4J_QUERY_TIMEOUT_SECONDS` (default 60; 0 disables) is a server-side SPARQL evaluation limit injected into every
`/repo` and `/api` request proxied to RDF4J. The public edge cuts clients at 10 s, but that never stops server-side
evaluation: abandoned heavy queries pile up and can spiral the changeset overlay (2026-08-21 CPU storm). Keep it well
above the edge timeout: aborted evaluations concurrent with writes are a suspected corruption trigger upstream
(rdf4j#5960/#4806), so this is a backstop, not a first-line limit.

## Fetching socket timeout

`NANOPUB_QUERY_FETCHING_SOCKET_TIMEOUT` (default 30000 ms) is the client-side socket timeout for the app's own reads
from RDF4J (`Utils.getHttpRequestConfig`). The former 10 s default was too aggressive for a store that is slow rather
than broken: the read raises `QueryInterruptedException`, the loader's generic retry loops treat that as transient, and
the retries add load, which makes the next read slower still. That storm saturated a fleet node on 2026-08-31. A
timeout costs nothing when reads are fast, and reads normally complete in tens of milliseconds.

Keep this in step with the default in `Utils.getHttpRequestConfig`, or the code and the deployment disagree about what
"default" means.

Trade-off: a genuinely hung backend now surfaces 3x slower, and each stuck read holds a pool connection 3x longer.
Acceptable because detection no longer depends on it: `JellyNanopubLoader` logs "Loader wedged" after
`WEDGE_ALERT_THRESHOLD` consecutive failures with no counter movement.

## FORCE_RESYNC

A one-shot repair/resync switch: it re-streams the whole registry at startup. Per-repo `isLoaded` checks skip
everything already present, so without a store wipe this fills holes (missing nanopubs) idempotently. Set it for one
`up -d`, then recreate again without it so later restarts don't re-trigger the sweep.

Only on a wiped or out-of-rotation instance. On a live one the sweep cycles all ~2,800 LMDB repos through the 100-slot
repo cache against live traffic: point-reads hit the client timeout, batches die on retry backoff (~50 nanopubs/min,
ETA days), and the loader state is clobbered meanwhile (status `LOADING_INITIAL`, counter at sweep position), as
measured on 2026-08-21. For a bounded hole of recent nanopubs, rewind the load counter instead (see
[operations](operations.md#a-gap-of-recent-nanopublications)): the update loop re-processes only that window in under a
minute.

## Internal URL for federated queries

`NANOPUB_QUERY_INTERNAL_URL` is the in-cluster base URL used for rewriting SERVICE clauses and query endpoints. It
defaults to `http://query:9393/` (this service); only set it if the app is reachable under a different in-cluster
address. Do not point it at the public URL: federated `/api` traffic would then loop through the public edge and
compete with external clients for the reverse proxy's connection limits (issue #142, incident 2026-07-28).

The RDF4J container gets the same variable for the healthcheck's federation probe (see [below](#healthcheck)), so the
probe tests the real federation route. Set it explicitly empty to disable the probe.

## Shutdown order and grace periods

An aborted LMDB write is the incomplete-rollback condition behind the value-ID remap corruption of
eclipse-rdf4j/rdf4j#4775: the 2026-08-20 and 2026-08-24 events scrambled metadata across subjects and swallowed
nanopubs outright. Three settings keep shutdowns clean:

- **`depends_on: rdf4j`** on the query service: Compose stops dependents first, so the query app stops before RDF4J,
  and the store never has one of the app's writes in flight when it goes down. `depends_on` also orders startup (RDF4J
  first), which is harmless: the loader already retries while the backend comes up.
- **`stop_grace_period: 150s`** on the query service: Docker's default is 10 s, which SIGKILLs the JVM in the middle of
  whatever it was writing, the very thing the shutdown hook exists to avoid. Kept above
  `NANOPUB_QUERY_SHUTDOWN_QUIESCE_SECONDS` (90 s) so the quiesce can finish and still leave room for closing
  repositories; a full space-state build alone can run ~70 s. Raise both together or neither.
- **`stop_grace_period: 120s`** on RDF4J: a clean Tomcat shutdown has to close thousands of LMDB repos and measured
  ~100 s on kpxl (2026-07-31: kill at 13:55:14, port closed 13:56:54). At 10 s every stop became a SIGKILL, which is
  the hard-kill-mid-write condition implicated in issue #142. Forwarding SIGTERM from `init.sh` is pointless without
  giving the shutdown room to finish.

All of this can be undercut from outside Compose, by the Docker daemon's own stop timeout and by needrestart; see
[operations](operations.md#preparing-the-host-clean-shutdowns).

## Federation connection pool and Tomcat threads

`RDF4J_MAX_CONN_PER_ROUTE` (120), `RDF4J_MAX_CONN_TOTAL` (240) and `RDF4J_TOMCAT_MAX_THREADS` (240).

All SERVICE clauses in `/api` queries are rewritten to this instance's in-cluster URL (`NANOPUB_QUERY_INTERNAL_URL`,
default `http://query:9393/`), so the whole federation load shares a single HttpClient route inside the RDF4J
container. The RDF4J defaults (25 per route, 50 total) saturate under concurrent federated queries, starving every
`/api` call with "Timeout waiting for connection from pool" (incident 2026-07-28).

Correction 2026-08-20: the earlier sizing reasoned about the wrong failure mode, and the 100 -> 60 reduction made the
wedge *more* likely. (The claim that the worker pool is 150 was also wrong: `maxThreads="150"` sits in a
commented-out `<Executor>` block in the image's `server.xml` and is not in effect; the active 8080 connector uses
Tomcat's default 200.)

A thread dump taken on kpxl while genuinely wedged (2026-08-20 05:18Z) shows the real mechanism: a re-entrant deadlock
on this single route, not Tomcat thread starvation. The raw dump is not committed; the load-bearing numbers are
reproduced below.

- `RepositoryFederatedService.select` obtains SERVICE results via
  `SPARQLProtocolSession.getBackgroundTupleQueryResult`, which pins a pooled connection for the entire lifetime of
  result consumption.
- `ServiceQueryEvaluationStep` sits under `JoinIterator.getInstance`, so a SERVICE inside a join fires one request per
  binding. An outer query is therefore mid-iteration over result #1, holding a connection, when it asks the same pool
  for the connection for result #2.
- Snapshot: 60 of 60 connections held by `BackgroundTupleResult` producers blocked in `QueueIteration.put` on a full
  `ArrayBlockingQueue` (their consumers cannot drain), and 28 threads blocked in `acquireEndpoint`. 22 of those 28 were
  get-view-displays (`repositories/type_11daee46...`), which carries 3 nested SERVICE hops.

Nobody can proceed; only `connectionRequestTimeout` (10 s) breaks the cycle, which is why the symptom flaps rather
than staying dead. Crucially, a smaller pool deadlocks at lower concurrency, so "fewer leases are needed before the
route saturates", the old rationale for 60, was backwards.

Threads were never the binding constraint: the same dump had 42 http-nio workers against `maxThreads=400`. That is
also why the maxThreads A/B showed no benefit on its own. Raising the pool is what moves the deadlock threshold, and
the thread headroom is what makes it safe.

The invariant to preserve is still "outer + federated self-calls <= Tomcat workers", so `maxThreads` defaults
alongside a per-route pool of half its value. Change the two together or not at all. `entrypoint/init.sh` applies
`RDF4J_TOMCAT_MAX_THREADS` to the connector.

Second ceiling (2026-08-20, found the hard way): rdf4j 6.0.0 hardcodes LMDB's reader table at 256 slots, and stock
6.0.0 pins one slot per thread (ThreadLocal read txn, freed only on thread death). A 400-thread default therefore
drove stock hosts into intermittent "MDB_READERS_FULL: Environment maxreaders limit reached" on plain queries
(petapico, within a day of the raise). Hosts running the PR#5974 lmdbpool canary pooled their readers and were immune
(kpxl, no errors under the same load). So the default stays below 256 with headroom: 240 threads / 120 per route /
240 total. The default image (lmdbpool4, see [RDF4J version](#rdf4j-version)) includes PR#5974's pooled readers, so this
ceiling no longer applies to it; the defaults are kept until a raise has been measured, and a host may raise all three
in its `.env` (e.g. 400/200/400).

This is mitigation, not a cure: it raises the threshold above realistic concurrency but the circular wait remains
reachable. The fix is removing the nesting (get-view-displays 3 SERVICE hops -> 1, already validated byte-for-byte
identical) plus an upstream report against rdf4j.

## JVM options for RDF4J

- **`-XX:+ExitOnOutOfMemoryError`**: without it, a heap OOM is survivable in the worst possible way. On kpxl
  2026-08-05 the OOM killed the `http-nio-8080-Acceptor` thread (04:17:08) and then the Poller (04:17:30) while the JVM
  stayed alive spinning in GC. With no acceptor, the socket stays bound but nothing ever accepts: the backlog fills,
  the kernel drops SYNs, and every client sees a 10 s connect timeout. The process never exits, so
  `restart: unless-stopped` never fires, and with `RDF4J_HEALTHCHECK_RESTART=off` nothing else intervenes: the
  instance sat wedged for 11 hours while still reporting READY. Exiting on the first OOM turns that permanent outage
  into a ~2 min restart. It also composes with the healthcheck decision rather than fighting it: the JVM only dies when
  it is already unrecoverable, so this does not reintroduce the mid-write kill risk of issue #142 that turning
  restarts off avoided.
- **`-XX:MaxRAMPercentage`** (`RDF4J_MAX_RAM_PERCENTAGE`, default 50): the container has no `mem_limit`, so the JVM
  default of 25% of *host* RAM applied. Pinning it makes the ceiling explicit and independent of the host the stack
  lands on.
- **`-XX:+HeapDumpOnOutOfMemoryError`**: `/var/info` is host-mounted (`./data/info`), so the dump survives the exit
  above and can be opened after the fact. Sized in GB; check free space there before enabling on a small host.
- **`-XX:+PrintConcurrentLocks`**: makes a SIGQUIT (`kill -3`) thread dump include the "Locked ownable synchronizers"
  block, i.e. which thread *owns* each `java.util.concurrent` lock. Without it a plain `kill -3` shows waiters but not
  holders. On 2026-08-05 a dump showed 171 threads parked on one `SailSourceBranch` ReentrantLock with no way to tell
  who held it, and the image ships a JRE (no jcmd/jstack), so there was no second way to ask. Zero runtime cost until
  a dump is requested.

## Healthcheck

The RDF4J healthcheck runs two probes:

- **Direct query** against the `full` repo. If it succeeds, the instance is marked as "has been ready"
  (`/var/info/ready`). A restart is only forced when a probe fails *and* this container instance has been seen ready
  before; otherwise Tomcat would be killed mid-deploy during the start period (the default start interval of 5 s is
  well below the WAR deploy time). `init.sh` clears the marker on every container start.
- **Federation route**: SERVICE clauses in `/api` queries loop back through the instance's in-cluster URL, so RDF4J's
  federation HTTP pool can wedge (incident 2026-07-28) while direct queries stay healthy. This probe tests that exact
  route. It uses `NANOPUB_QUERY_INTERNAL_URL` (with trailing slash) and is skipped when that is explicitly set to
  empty.

`restart_tomcat` gives Tomcat up to 60 s to stop gracefully before the `kill -9`: dozens of LMDB repos need to close
cleanly, and the previous 10 s window meant effectively every restart was a hard kill, which the 2026-07-29 incident
(issue #142) showed can discard acknowledged writes held in a wedged store. The 10-minute backoff prevents restart
storms: a persistently wedged repo used to trigger a kill every probe interval, multiplying the data-loss windows; now
the probe reports unhealthy without re-killing until the backoff elapses.

**Auto-restart is off by default since 2026-07-31** (`RDF4J_HEALTHCHECK_RESTART=off`). Both probes still run and
still record failures, so a wedge is visible (the container goes unhealthy, timestamps land in `/var/info/`), but
nothing kills Tomcat.

Why: on 2026-07-31 the healthcheck killed Tomcat 10 times, 7 of them between 12:00 and 13:55, and it was not curing
anything. Each cycle cost ~110 s of downtime (13:55:14 kill -> 13:57:04 back up) and the store re-wedged within 11-20
minutes, so the restart only reset a clock. Meanwhile every kill is a roll of the issue #142 dice: acknowledged,
read-back-verified writes reverting. Trading a visible degradation for silent data loss is the wrong way round.

Attribution, so the next reader does not repeat our mistake: 8 of the 10 kills came from the first (direct-query)
probe, only 2 from the federation probe. `/var/info/restart` is the kill log; `/var/info/federation-restart` is written
*before* `restart_tomcat`, which often returns early on the backoff, so the two files are not comparable counts.
Disabling the federation probe alone would have prevented 2.

Re-enable with `RDF4J_HEALTHCHECK_RESTART=on` once the underlying LMDB `ValueStore$ReadTxn` leak is confirmed
resolved (present through 5.3.2 even after #5807; the pooled read transactions of PR#5974, in the default image,
should address it, but verify in production first; see eclipse-rdf4j/rdf4j#5970).

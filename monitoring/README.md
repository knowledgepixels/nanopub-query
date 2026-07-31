# Monitoring nanopub-query

`prometheus-alerts.yml` holds alerting rules for the loader. They exist because
of a specific gap, worth understanding before tuning them.

## Why these rules

On 2026-07-31 `query.knowledgepixels.com` stopped ingesting at 09:58:31Z and
stayed stuck for over two hours. Throughout, it served SPARQL in ~0.1 s and
reported `Nanopub-Query-Status: READY`. The public monitor rated it OK apart
from a checksum marked "outlier".

Two things made it invisible:

- **The monitor compares instances against each other.** It flags a checksum
  that disagrees with the consensus, which only works while at least one
  instance is healthy. A fleet-wide stall — a bad deploy, a shared upstream
  fault, one bug firing everywhere — produces unanimous agreement and reads as
  all-clear.
- **The metrics that would have caught it were already correct, and nothing was
  watching them.** `registry_loader_breaker_active` would have flipped within
  seconds and `registry_loader_last_successful_batch_age_seconds` would have
  climbed from the first failure. Both were exposed the whole time.

So the fix is mostly configuration, not instrumentation. The one metric added
afterwards is `registry_loader_sync_lag_nanopubs`, which reports the outcome
(how far behind the registry this instance is) rather than the loader's internal
state, and is absolute rather than relative — it fires even when every instance
is equally broken.

## Scrape setup

`docker-compose.yml` binds the metrics port to loopback only:

```yaml
ports:
  - "127.0.0.1:9394:9394"
```

Prometheus therefore has to run on the same host (or share its network
namespace). Do not publish 9394 publicly to work around this — the endpoint is
unauthenticated.

```yaml
scrape_configs:
  - job_name: nanopub-query
    static_configs:
      - targets: ['127.0.0.1:9394']
```

The `job_name` matters: `NanopubQueryMetricsUnreachable` matches on
`up{job="nanopub-query"}`.

Load the rules with `rule_files: [ /path/to/prometheus-alerts.yml ]` and verify
with `promtool check rules monitoring/prometheus-alerts.yml`.

## Reading the signals together

`last_successful_batch_age_seconds` and `sync_lag_nanopubs` look redundant and
are not. The lag is computed against the registry count forwarded by the most
recent successful poll. If the poll itself is what broke, both the loaded count
and the forwarded registry count freeze together and the lag reads a reassuring
`0` forever. The staleness gauge is what catches that case. Conversely, if the
loader ticks happily but falls behind for some reason that never throws, the
staleness gauge stays low and only the lag moves.

`sync_lag_nanopubs` reports `-1` when either count is unavailable — before the
first registry poll, or if the forwarded value is unparseable. That is
deliberately distinct from `0`, which asserts the instance is genuinely in sync.
Real lags are clamped at zero, so `-1` can only ever mean "unknown". Any rule
written over this metric should use `> 0` rather than `!= 0`.

## When an alert fires

Start with the loader's own error, which carries the stack trace:

```bash
docker compose logs query --since <time> 2>&1 | grep -A30 "Failed to load updates"
```

Then check whether RDF4J was restarted underneath it — the healthcheck kills
Tomcat by design when its probes fail, and records each one:

```bash
tail -20 data/info/restart data/info/federation-restart
```

The absence of `Retrying load of <...> to repo` lines alongside a
`Could not update the nanopub counter in DB` means the failure is in the
admin-repo write, before `loadBatch` ever runs.

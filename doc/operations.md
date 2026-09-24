# Operating a Nanopub Query Instance

This guide is for admins running their own Query instance. It picks up where the
[README](../README.md#running-an-instance) leaves off: what the host needs, how to keep the store safe, how to upgrade,
and what to do when something is missing. The reasons behind the individual settings in `docker-compose.yml` are in
the [deployment rationale](deployment-rationale.md).

## Requirements

A Linux host with Docker Engine and the Docker Compose plugin, and a reverse proxy for TLS (see
[nginx.conf](../nginx.conf)).

For a sense of scale, as of September 2026, with about 93,000 nanopublications:

- The RDF4J store takes about 12 GB on disk (measured at 88,000 nanopublications), spread over about 2,800
  repositories, one for each public key and each nanopublication type. It grows with the number of nanopublications.
- The RDF4J process uses about 6 GB of memory.
- The initial load from a registry on the same host takes about 2 hours.
- Our instances run on hosts with 6 to 14 CPU cores, 32 to 64 GB of RAM and NVMe SSDs, most of them together with a
  registry and some with other services too.

A dedicated host with 6 cores, 32 GB of RAM and 100 GB of SSD space is comfortable. Things to keep in mind on a
smaller or shared host:

- RDF4J's heap limit is set as a share of the host's RAM, 50% by default. If other services share the host, lower it
  with `RDF4J_MAX_RAM_PERCENTAGE` in `.env` (we use 25 on a 32 GB host that also runs Nanodash).
- After an out-of-memory error, RDF4J writes a heap dump to `data/info/`, which can take several GB.
- Under heavy query load, RDF4J uses all the CPU it can get. If it shares the host with other services, cap it in
  `docker-compose.override.yml`:

  ```yaml
  services:
    rdf4j:
      cpus: 4.0
      mem_limit: 12g
  ```

## Choosing a Registry

`REGISTRY_FIXED_URL` names the one [Nanopub Registry](https://github.com/knowledgepixels/nanopub-registry) the
instance loads from. The instance serves what that registry covers, so a registry with restricted coverage (e.g. of
selected types) gives a Query instance with the same restriction. Ideally, run a registry on the same host: the initial
load is much faster than across the internet, and the Query instance does not depend on someone else's registry.

A registry that reports itself as a test instance is ignored, unless `NANOPUB_QUERY_ALLOW_TEST_REGISTRY=true` is set.

## Preparing the Host: Clean Shutdowns

The RDF4J store can be corrupted if it is killed in the middle of a write. The Compose setup gives both containers
enough time to shut down cleanly (see [rationale](deployment-rationale.md#shutdown-order-and-grace-periods)), but on a
typical Ubuntu server two host-level mechanisms cut that time short. Both caused real corruption on our instances.
Check them on every host that runs an instance.

**1. Docker's own stop timeout.** When the host shuts down or reboots (e.g. automatically after a kernel update),
systemd gives the Docker daemon 90 seconds to stop all containers, and then kills it. RDF4J alone may need 120 seconds.
Raise the limit:

```bash
sudo mkdir -p /etc/systemd/system/docker.service.d
printf '[Service]\nTimeoutStopSec=300\n' | sudo tee /etc/systemd/system/docker.service.d/stop-timeout.conf
sudo systemctl daemon-reload
systemctl show docker.service --property=TimeoutStopUSec   # should say 5min
```

`daemon-reload` only re-reads the configuration; it does not restart Docker, so this is safe on a running instance.

**2. needrestart restarting containerd.** After `unattended-upgrades` installs library updates, `needrestart`
restarts the services that use them. That includes `containerd`, and restarting it kills all containers without a
clean stop. Exclude Docker and containerd:

```bash
echo '$nrconf{override_rc}{qr(^(docker|containerd)\.service$)} = 0;' | sudo tee /etc/needrestart/conf.d/no-docker.conf
```

To verify, check that `systemctl show containerd --property=ActiveEnterTimestamp` does not change across the next
upgrade window. (`needrestart` still lists the two services as needing a restart; that is expected.) The trade-off is
that Docker keeps running against the old libraries until you restart it yourself, at a moment of your choosing.

Beyond that: stop the instance only with `docker compose stop` or `docker compose down`, never with `docker kill` or a
shortened timeout (`-t`).

## Upgrading

Releases are listed on [GitHub](https://github.com/knowledgepixels/nanopub-query/releases), with their changes in the
[changelog](../CHANGELOG.md), and published as `nanopub/query` images on Docker Hub, tagged with their version. By
default the instance runs the `latest` tag. To control when upgrades happen, pin a version in `.env`:

```bash
NANOPUB_QUERY_IMAGE_TAG=1.28.1
```

To upgrade, update the repository checkout as well, since `docker-compose.yml` changes along with the app, then set
the new version and restart:

```bash
git pull
docker compose pull
docker compose up -d
```

Before upgrading, read the release notes of every version in between. A release whose changes only apply to
nanopublications loaded afterwards says "requires re-ingest": for those, [wipe and re-ingest](#wipe-and-re-ingest)
after upgrading, or older nanopublications stay in the old form.

RDF4J is upgraded separately, via `RDF4J_IMAGE` and `RDF4J_IMAGE_TAG` in `.env`; set both, since the default image is
`nanopub/rdf4j-workbench` while official releases are published as `eclipse/rdf4j-workbench`. Keep the default,
`6.0.0-lmdbpool4`, unless you have read the [rationale](deployment-rationale.md#rdf4j-version): neither stock 6.0.0
nor 6.1.0 is safe for this workload. Going from 5.x to 6.x requires a wipe and re-ingest.

## Checking on an Instance

- **Loading progress**: every response carries the `Nanopub-Query-Status` header (`LOADING_INITIAL`,
  `LOADING_UPDATES` or `READY`), and `Nanopub-Query-Loaded-Nanopub-Count` should match the registry's count in
  `Nanopub-Query-Registry-Nanopub-Count`, apart from nanopublications that arrived in the last few seconds:

  ```bash
  curl -sI http://localhost:9393/ | grep -i '^nanopub-query'
  ```

- **Container health**: `docker compose ps` shows whether RDF4J's healthcheck passes. Failed probes are logged with
  timestamps in `data/info/`.
- **Logs**: `docker compose logs -f query` for the loader, `docker compose logs -f rdf4j` for the store.
- **Metrics and alerts**: Prometheus metrics on `localhost:9394/metrics`; see [monitoring](../monitoring/README.md)
  for alerting rules that catch a stalled loader.
- **The public fleet**: [monitor.knowledgepixels.com](https://monitor.knowledgepixels.com/) shows the public
  instances side by side.

## Repairing

### Nanopublications missing from single repositories

This is handled automatically. Every nanopublication is written to several repositories (`full`, `meta`, its
public-key and type repositories, etc.), and a background sweep checks every few minutes whether recently loaded
nanopublications are present in all of them, and re-loads them where they are not. To make the sweep re-check older
nanopublications too, lower its checkpoint with [scripts/set-reconciliation-checkpoint.sh](../scripts/set-reconciliation-checkpoint.sh).

### A gap of recent nanopublications

If nanopublications from a recent window are missing altogether, rewind the load counter to just below the gap, and
the update loop re-processes only that window. With the Query app stopped and RDF4J running:

```bash
docker compose stop query
curl -s http://localhost:8081/rdf4j-server/repositories/admin/statements --data-urlencode 'update=
  PREFIX npa: <http://purl.org/nanopub/admin/>
  PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
  DELETE { GRAPH npa:graph { npa:thisRepo npa:hasRegistryLoadCounter ?c ; npa:hasStatus ?s } }
  INSERT { GRAPH npa:graph { npa:thisRepo npa:hasRegistryLoadCounter "92700"^^xsd:long ; npa:hasStatus "READY" } }
  WHERE  { GRAPH npa:graph { npa:thisRepo npa:hasRegistryLoadCounter ?c ; npa:hasStatus ?s } }'
docker compose start query
```

Replace `92700` with a registry load counter just below the gap. Nanopublications that are already present are
skipped.

### Gaps throughout the store

`FORCE_RESYNC=true` makes the instance re-stream the whole registry at startup and fill in whatever is missing. Set it
in `.env` for a single `docker compose up -d`, then remove it and run `docker compose up -d` again. Only do this on an
instance that is not serving public traffic: on a live instance the sweep competes with queries and can take days (see
[rationale](deployment-rationale.md#force_resync)).

### Wipe and re-ingest

For a corrupted store, a release that requires re-ingest, or an RDF4J upgrade across a major version, start the store
from scratch. Move the old store aside rather than deleting it, until the new one has caught up:

```bash
docker compose down
sudo mv data/rdf4j/data data/rdf4j/data.old
./init-dirs.sh
docker compose up -d
```

The instance then loads everything from the registry again, like on its first start.

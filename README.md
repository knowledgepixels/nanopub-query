[![Coverage Status](https://coveralls.io/repos/github/knowledgepixels/nanopub-query/badge.svg)](https://coveralls.io/github/knowledgepixels/nanopub-query)

# Nanopub Query

Nanopub Query is the second-generation query service for nanopublications.

## Available Instances

You can check out Nanopub Query at these instances:

- https://query.knowledgepixels.com/
- https://query.nanodash.net/
- https://query.petapico.org/

The current status of the live instances is shown at https://monitor.knowledgepixels.com/.

## Documentation

See the [JavaDocs](https://javadoc.io/doc/com.knowledgepixels/nanopub-query/latest/index.html) for the API and
source code documentation.

## Repos and Triples

Each nanopublications is loaded into different repos in the form of RDF4J triple stores. There are these general repos:

- `meta`: Stores some specific "admin-graph metadata" of the nanopublications (see below), but not the nanopublications
  themselves
- `full`: Stores all nanopublications in full and their admin-graph metadata (this is not scalable on the long term, so
  will be deprecated in the medium-term future)
- `last30d`: Stores all nanopublications of the last 30 days and their admin-graph metadata
- `text`: Stores all nanopublications for full-text search
- `admin`: Stores some further admin info, such as the full pubkeys for their hash values
- `empty`: Empty repo from which other repos can be accessed via the SPARQL `service` keyword

On top of that, there are these specific repos:

- `pubkey`: For each public key used to sign a nanopublication, a separate repo is created
- `type`: For each nanopub type, a separate repo is created too

Two admin graphs (`npa:graph` and `npa:networkGraph`) are created with metadata about the nanopublications.
The [admin triple table](doc/admin-triple-table.csv) shows the details.

## Running an Instance

A Query instance loads its nanopublications from a
[Nanopub Registry](https://github.com/knowledgepixels/nanopub-registry), ideally one you run next to it. It runs
with Docker Compose, as two containers: the Query app itself and the RDF4J triple store behind it.

Create the data directories, copy the `docker-compose.override.yml.template` file to `docker-compose.override.yml`,
and adjust the settings in its Section 1 (public URL and registry):

```bash
./init-dirs.sh
cp docker-compose.override.yml.template docker-compose.override.yml
```

Then start it:

```bash
docker compose up -d
```

The Query app serves plain HTTP on `localhost:9393` (plus a metrics endpoint on `localhost:9394`), and RDF4J on
`localhost:8081`. None of these have authentication, so they are bound to localhost only. To make the instance
publicly reachable via HTTPS, run a reverse proxy on the host that terminates TLS: [nginx.conf](nginx.conf) is the
reference configuration, which also restricts the SPARQL endpoints to read-only queries and answers CORS preflight
requests.

On first start, the instance loads all nanopublications from the registry, which takes a while. Every response
reports the progress in its headers: `Nanopub-Query-Status` goes from `LOADING_INITIAL` to `READY`, and
`Nanopub-Query-Loaded-Nanopub-Count` approaches the registry's count in `Nanopub-Query-Registry-Nanopub-Count`:

```bash
curl -sI http://localhost:9393/ | grep -i '^nanopub-query'
```

Stop the instance with `docker compose stop` or `docker compose down` only, and give it time: the stack is
configured to shut down its store cleanly, which can take a few minutes. Killing the containers mid-write can corrupt
the store. For alerting rules on the loader, see [monitoring](monitoring/README.md).

## Development

To build and run from the local sources, run:

```bash
./run.sh
```

Optional development features like remote JVM debugging (`localhost:5005`) can be enabled in the development section
of `docker-compose.override.yml`.

## License

This software is made available under the MIT license. See LICENSE.txt for the details.

For an overview of the dependencies and their licenses, run `mvn project-info-reports:dependencies` and then visit
`target/reports/dependencies.html`.

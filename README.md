# Nanopub Query

Nanopub Query is the second-generation query service for nanopublications.

## Available Instances

You can check out Nanopub Query at these instances:

- https://query.knowledgepixels.com/
- https://query.np.trustyuri.net/
- https://query.petapico.org/


## Documentation

See the [JavaDocs](https://javadoc.io/doc/com.knowledgepixels/nanopub-query/latest/index.html) for the API and
source code documentation.


## Repos and Triples

Each nanopublications is loaded into different repos in the form of RDF4J triple stores. There are these general repos:

- `meta`: Stores some specific "admin-graph metadata" of the nanopublications (see below), but not the nanopublications themselves
- `full`: Stores all nanopublications in full and their admin-graph metadata (this is not scalable on the long term, so will be deprecated in the medium-term future)
- `last30d`: Stores all nanopublications of the last 30 days and their admin-graph metadata
- `text`: Stores all nanopublications for full-text search
- `admin`: Stores some further admin info, such as the full pubkeys for their hash values
- `empty`: Empty repo from which other repos can be accessed via the SPARQL `service` keyword

On top of that, there are these specific repos:

- `pubkey`: For each public key used to sign a nanopublication, a separate repo is created
- `type`: For each nanopub type, a separate repo is created too

Two admin graphs (`npa:graph` and `npa:networkGraph`) are created with metadata about the nanopublications.
The [admin triple table](doc/admin-triple-table.csv) shows the details.


## General Setup

Get pre-packaged data (optional):

    $ wget https://zenodo.org/records/14260335/files/rdf4j-20241202.tar.gz
    $ tar -xzf rdf4j-20241202.tar.gz

Init directories:

    $ ./init-dirs.sh

Check `docker-compose.yml` and make any adjustments in a new file `docker-compose.override.yml`.


## Deadlock Problem Workaround

There is a deadlock problem with RDF4J ([details](https://github.com/eclipse-rdf4j/rdf4j/discussions/5120)), which
requires some specific web server configuration to work around it. Specifically, concurrent requests per repo have
to be avoided, with a configuration like in [this nginx example](nginx.conf).


## Launch

Start with Docker Compose:

    $ sudo docker compose up -d


## License

This software is made available under the MIT license. See LICENSE.txt for the details.

For an overview of the dependencies and their licenses, run `mvn project-info-reports:dependencies` and then visit `target/reports/dependencies.html`.

# Nanopub Query

Next-generation query service for nanopublications.

## Setup

Get pre-Packaged Data:

    $ wget https://zenodo.org/records/10658121/files/mongodb7-x-20240214.tar.gz
    $ tar -xvzf mongodb7-x-20240214.tar.gz

    $ wget https://zenodo.org/records/10658121/files/rdf4j-20240214.tar.gz
    $ tar -xvzf rdf4j-20240214.tar.gz

Init directories:

    $ ./init-dirs.sh

Check `docker-compose.yml` and make any adjustments in a new file `docker-compose.override.yml`.

Start with Docker Compose:

    $ sudo docker compose up -d

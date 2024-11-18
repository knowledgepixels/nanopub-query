# Nanopub Query

Nanopub Query is the second-generation query service for nanopublications.

## General Setup

Get pre-packaged data (optional):

    $ wget https://zenodo.org/records/11125050/files/rdf4j-20240507.tar.gz
    $ tar -xvzf rdf4j-20240507.tar.gz

Init directories:

    $ ./init-dirs.sh

Check `docker-compose.yml` and make any adjustments in a new file `docker-compose.override.yml`.


## Connection to Publishing Layer

In the near future, Nanopub Query will get its initial nanopublications and regular updates via the
[Nanopub Registry](https://github.com/knowledgepixels/nanopub-registry), which isn't yet available.

So, currently, it relies on a small [autofetch script](scripts/autofetch.sh) to get the initial
nanopublications, and then needs to connect to a [nanopub-server](https://github.com/tkuhn/nanopub-server)
instance to get regular updates.

To receive updates from such a nanopub-server instance, e.g. via
[signed-nanopub-services](https://github.com/peta-pico/signed-nanopub-services), that nanopub-server needs
to be configured to send new nanopublications to the Nanopub Query instance like this:

    services:
      server:
        environment:
          - 'NPS_POST_NEW_NANOPUBS_TO=https://query.example1.com/ https://query.example2.com/'

## Launch

Start with Docker Compose:

    $ sudo docker compose up -d


## License

Copyright (C) 2024 Knowledge Pixels

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as
published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see https://www.gnu.org/licenses/.

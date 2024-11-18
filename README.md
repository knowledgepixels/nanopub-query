# Nanopub Query

Next-generation query service for nanopublications.

## Setup

Get pre-Packaged Data:

    $ wget https://zenodo.org/records/11125050/files/rdf4j-20240507.tar.gz
    $ tar -xvzf rdf4j-20240507.tar.gz

Init directories:

    $ ./init-dirs.sh

Check `docker-compose.yml` and make any adjustments in a new file `docker-compose.override.yml`.

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

# Packaging DBs

## Building packages

Stop all services:

    $ sudo docker compose stop

Create zip file of rdf4j:

    $ sudo tar --exclude='data/rdf4j/logs/*' --exclude='data/rdf4j/data/server/logs/*' -czvf rdf4j.tar.gz data/rdf4j

Start the services again:

    $ sudo docker-compose start

To clarify versions:

    $ mv rdf4j.tar.gz rdf4j-20231027.tar.gz

## Using packages

Downloading and then unzipping:

    $ tar -xvzf rdf4j-20231027.tar.gz


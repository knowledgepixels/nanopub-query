# Packaging DBs

## Building packages

Stop all services:

    $ sudo docker compose stop

Create zip file of mongodb:

    $ sudo tar -czvf mongodb7.tar.gz data/mongodb

Create zip file of rdf4j:

    $ sudo tar --exclude='data/rdf4j/logs/*' --exclude='data/rdf4j/data/server/logs/*' -czvf rdf4j.tar.gz data/rdf4j

Start the services again:

    $ sudo docker-compose start


## Removing journal ID in MongoDB

Extract MongoDB in empty directory:

    $ tar -xvzf mongodb7.tar.gz

Remove lock files, if any (might need sudo):

    $ rm -f data/mongodb/*.lock

Run MongoDB:

    $ sudo docker run -v $(pwd)/data/mongodb:/data/db -p 27017:27017 -d mongo:7

Check journal ID (`mongosh` might have to installed via `apt install mongodb-org` and installation sources added first):

    $ mongosh --eval "db.getSiblingDB('nanopub-server').getCollection('journal').find({'_id':'journal-id'});"

Set journal ID to 0:

    $ mongosh --eval "db.getSiblingDB('nanopub-server').getCollection('journal').findAndModify({'query':{'_id':'journal-id'},'update':{'_id':'journal-id','value':'0'}});"

Check again:

    $ mongosh --eval "db.getSiblingDB('nanopub-server').getCollection('journal').find({'_id':'journal-id'});"

Stopping MongoDB:

    $ sudo docker stop DOCKER-CONTAINER-ID

Remove lock files again, if any, and diagnostic data:

    $ sudo rm -f data/mongodb/*.lock
    $ sudo rm -rf data/mongodb/diagnostic.data

Making new archive:

    $ sudo tar -czvf mongodb7-x.tar.gz data/mongodb


## Adding date to file names

To clarify versions:

   $ mv rdf4j.tar.gz rdf4j-20231027.tar.gz
   $ mv mongodb7-x.tar.gz mongodb7-x-20231027.tar.gz


## Using packages

Downloading and then unzipping:

    $ tar -xvzf mongodb7x-20231027.tar.gz
    $ tar -xvzf rdf4j-20231027.tar.gz


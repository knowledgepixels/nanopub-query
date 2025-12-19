# Nanopub Query Developer Notes

## Manually accessing RDF4J SPARQL endpoint:

    $ curl -H "Accept: text/csv" 'http://localhost:8080/rdf4j-server/repositories/test?query=select%20%2A%20%7B%20%3Fa%20%3Fb%20%3Fc%20%7D&queryLn=sparql'

## Count open connections

All:

    $ netstat -an | wc -l

By nanopub-query:

    $ sudo nsenter -t $(sudo docker inspect -f "{{.State.Pid}}" nanopub-query-query-1) -n netstat -an | wc -l

Test internal connection from query to rdf4j container:

    $ sudo docker compose exec -it query bash 
    # curl rdf4j:8080/rdf4j-server/repositories/full?query=select%20%2A%20where%20%7B%20graph%20%3Chttps%3A%2F%2Fw3id.org%2Fnp%2FRAdxdsL5vtExmiaydCI0yJCCoE5lkNksGr46KPEJUR37k%23assertion%3E%20%7B%20%3Fs%20%3Fp%20%3Fo%20%7D%20%7D
    # curl -v -X OPTIONS query:9393/repo/full?query=select%20%2A%20where%20%7B%20graph%20%3Chttps%3A%2F%2Fw3id.org%2Fnp%2FRAdxdsL5vtExmiaydCI0yJCCoE5lkNksGr46KPEJUR37k%23assertion%3E%20%7B%20%3Fs%20%3Fp%20%3Fo%20%7D%20%7D

## Packaging DB

Stop all services:

    $ sudo docker compose stop

Create zip file of rdf4j:

    $ sudo tar --exclude=logs -czvf nanopub-query-data-rdf4j.tar.gz data/rdf4j

Start the services again:

    $ sudo docker-compose start

To clarify versions:

    $ mv nanopub-query-data-rdf4j.tar.gz nanopub-query-data-rdf4j-20250331.tar.gz

Unpacking after download:

    $ tar -xvzf nanopub-query-data-rdf4j-20250331.tar.gz
    $ ./init-dirs.sh


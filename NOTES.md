# Notes for Nanopub Query

## Update Dependencies

    $ mvn versions:use-latest-versions
    $ mvn versions:update-properties

## Manual POST Request

    $ curl -X POST http://localhost:9300 -H "Content-Type: multipart/form-data" -d @scratch/nanopub.trig

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

Stresstest notes:

    $ while (true); do curl -L 'https://query.np.trustyuri.net/api/RAsGgFwseoLaCgyxlP21dlGeqr8BrpRPHht_oLf_49ESQ/get-latest-bdj-nanopubs-by-author?author=https://orcid.org/0000-0002-2151-1278'; done

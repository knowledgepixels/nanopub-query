# Notes for Nanopub Query

## Update Dependencies

    $ mvn versions:use-latest-versions
    $ mvn versions:update-properties

## Manual POST Request

    $ curl -X POST http://localhost:9300  -H "Content-Type: multipart/form-data" -d @scratch/nanopub.trig

## Manually accessing RDF4J SPARQL endpoint:

    $ curl -H "Accept: text/csv" 'http://localhost:8080/rdf4j-server/repositories/test?query=select%20%2A%20%7B%20%3Fa%20%3Fb%20%3Fc%20%7D&queryLn=sparql'

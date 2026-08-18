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

    $ docker compose stop

Create zip file of rdf4j:

    $ sudo tar --exclude=logs -czvf nanopub-query-data-rdf4j.tar.gz data/rdf4j

Start the services again:

    $ docker compose start

To clarify versions:

    $ mv nanopub-query-data-rdf4j.tar.gz nanopub-query-data-rdf4j-20250331.tar.gz

Unpacking after download:

    $ tar -xvzf nanopub-query-data-rdf4j-20250331.tar.gz
    $ ./init-dirs.sh

### Caveats

**This restores an instance from its own backup. It is not a way to seed one
instance from another.** The archive includes the admin repo, which records the
registry setup-id the data was loaded from. Restore it onto a different instance
and that id will not match the registry that instance is configured against, so
the loader treats the store as foreign and bootstraps from counter 0 — you get
the full ingest anyway, on top of a store that already holds the data. Tried
2026-08-18 (seeding petapico from a nanodash snapshot): the load counter reset
and the instance never converged; wiping `data/rdf4j/data` and syncing from empty
was what worked. To move an instance to a new machine, restore its *own* backup;
to populate a different instance, sync from its registry.

**The archive is version-specific.** RDF4J 6.x changed the LMDB value-ID layout
and refuses to open a 5.x store ("Directory contains data from an older
unsupported version of LmdbStore"), and 5.x cannot open a 6.x store either. Label
archives with the server version as well as the date — the tarball in this repo
from 2026-08-12 is a **5.3.2** store, despite that deploy having been recorded at
the time as 6.0.0.


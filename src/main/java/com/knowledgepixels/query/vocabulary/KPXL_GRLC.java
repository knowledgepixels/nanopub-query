package com.knowledgepixels.query.vocabulary;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Namespace;
import org.nanopub.vocabulary.VocabUtils;

public class KPXL_GRLC {

    public static final String NAMESPACE = "https://w3id.org/kpxl/grlc/";
    public static final String PREFIX = "kpxl_grlc";
    public static final Namespace NS = VocabUtils.createNamespace(PREFIX, NAMESPACE);

    /**
     * IRI for relation to link a grlc query instance to its SPARQL endpoint URL.
     */
    public static final IRI ENDPOINT = VocabUtils.createIRI(NAMESPACE, "endpoint");

    /**
     * IRI for relation to link a grlc query instance to its SPARQL template.
     */
    public static final IRI SPARQL = VocabUtils.createIRI(NAMESPACE, "sparql");

}

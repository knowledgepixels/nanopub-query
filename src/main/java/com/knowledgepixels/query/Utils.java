package com.knowledgepixels.query;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.exec.environment.EnvironmentUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import com.google.common.hash.Hashing;

/**
 * Utils class for Nanopub Registry.
 */
public class Utils {

    private Utils() {
    }  // no instances allowed

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    /**
     * IRI for the predicate that indicates that a hash value is associated with an object.
     */
    public static final IRI IS_HASH_OF = vf.createIRI("http://purl.org/nanopub/admin/isHashOf");

    /**
     * Prefix for the hash values stored in the admin graph.
     */
    public static final IRI HASH_PREFIX = vf.createIRI("http://purl.org/nanopub/admin/hash/");

    private static Map<String, Value> hashToObjMap;

    /**
     * Returns reverse hashing map.
     *
     * @return Map from hashes to their original objects
     */
    static Map<String, Value> getHashToObjectMap() {
        if (hashToObjMap == null) {
            hashToObjMap = new HashMap<>();
            try (RepositoryConnection conn = TripleStore.get().getAdminRepoConnection()) {
                TupleQuery query = conn.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * { graph ?g { ?s ?p ?o } }");
                query.setBinding("g", NanopubLoader.ADMIN_GRAPH);
                query.setBinding("p", IS_HASH_OF);
                try (TupleQueryResult r = query.evaluate()) {
                    while (r.hasNext()) {
                        BindingSet b = r.next();
                        String hash = b.getBinding("s").getValue().stringValue();
                        hash = StringUtils.replace(hash, HASH_PREFIX.toString(), "");
                        hashToObjMap.put(hash, b.getBinding("o").getValue());
                    }
                }
            }
        }
        return hashToObjMap;
    }

    /**
     * Returns the original object for the given hash value.
     *
     * @param hash The hash value
     * @return The original object
     */
    public static Value getObjectForHash(String hash) {
        return getHashToObjectMap().get(hash);
    }

    /**
     * Creates a hash value for the object and remembers it.
     *
     * @param obj Object to be hashed
     * @return hash value
     */
    public static String createHash(Object obj) {
        String hash = Hashing.sha256().hashString(obj.toString(), StandardCharsets.UTF_8).toString();

        if (!getHashToObjectMap().containsKey(hash)) {
            Value objV = getValue(obj);
            try (RepositoryConnection conn = TripleStore.get().getAdminRepoConnection()) {
                conn.add(vf.createStatement(vf.createIRI(HASH_PREFIX + hash), IS_HASH_OF, objV, NanopubLoader.ADMIN_GRAPH));
            }
            getHashToObjectMap().put(hash, objV);
        }
        return hash;
    }

    /**
     * Returns the object as a Value object.
     *
     * @param obj Input object
     * @return A Value object with the string content of the input object
     */
    static Value getValue(Object obj) {
        if (obj instanceof Value) {
            return (Value) obj;
        } else {
            return vf.createLiteral(obj.toString());
        }
    }

    /**
     * Returns a short "name" for the given public key
     *
     * @param pubkey Public key string
     * @return Short "name"
     */
    public static String getShortPubkeyName(String pubkey) {
        return pubkey.replaceFirst("^(.).{39}(.{5}).*$", "$1..$2..");
    }

    /**
     * Executes a query on the given connection to return the first objects matching the input.
     *
     * @param conn  The repository connection
     * @param graph Graph (=context) IRI
     * @param subj  Subject IRI
     * @param pred  Predicate IRI
     * @return the first object to match the pattern
     */
    public static Value getObjectForPattern(RepositoryConnection conn, IRI graph, IRI subj, IRI pred) {
        TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * { graph <" + graph.stringValue() + "> { <" + subj.stringValue() + "> <" + pred.stringValue() + "> ?o } }").evaluate();
        try (r) {
            if (!r.hasNext()) return null;
            return r.next().getBinding("o").getValue();
        }
    }

    /**
     * Executes a query on the given connection to return all objects matching the input.
     *
     * @param conn  The repository connection
     * @param graph Graph (=context) IRI
     * @param subj  Subject IRI
     * @param pred  Predicate IRI
     * @return a list of all objects matching the pattern
     */
    public static List<Value> getObjectsForPattern(RepositoryConnection conn, IRI graph, IRI subj, IRI pred) {
        List<Value> values = new ArrayList<>();
        TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * { graph <" + graph.stringValue() + "> { <" + subj.stringValue() + "> <" + pred.stringValue() + "> ?o } }").evaluate();
        try (r) {
            while (r.hasNext()) {
                values.add(r.next().getBinding("o").getValue());
            }
            return values;
        }
    }

    /**
     * Returns the system environment variable content for the given environment variable name.
     *
     * @param envVarName   environment variable name
     * @param defaultValue default value if not found
     * @return environment variable value
     */
    public static String getEnvString(String envVarName, String defaultValue) {
        try {
            String s = EnvironmentUtils.getProcEnvironment().get(envVarName);
            if (s != null && !s.isEmpty()) {
                return s;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return defaultValue;
    }

    /**
     * Returns the system environment variable content as an integer for the given environment variable name.
     *
     * @param envVarName   environment variable name
     * @param defaultValue default value if not found
     * @return environment variable value interpreted as an integer
     */
    public static int getEnvInt(String envVarName, int defaultValue) {
        try {
            String s = getEnvString(envVarName, null);
            if (s != null) {
                return Integer.parseInt(s);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return defaultValue;
    }

    /**
     * Default query to be shown in YASGUI client.
     */
    public static final String defaultQuery = """
            prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            prefix dct: <http://purl.org/dc/terms/>
            prefix np: <http://www.nanopub.org/nschema#>
            prefix npa: <http://purl.org/nanopub/admin/>
            prefix npx: <http://purl.org/nanopub/x/>
            
            select * where {
            ## Info about this repo:
              npa:thisRepo ?pred ?obj .
            ## Search for nanopublications:
            # graph npa:graph {
            #   ?np npa:hasValidSignatureForPublicKey ?pubkey .
            #   filter not exists { ?npx npx:invalidates ?np ; npa:hasValidSignatureForPublicKey ?pubkey . }
            #   ?np dct:created ?date .
            #   ?np np:hasAssertion ?a .
            #   optional { ?np rdfs:label ?label }
            # }
            } limit 10""";

    /**
     * Get the HTTP request config for fetching nanopublications.
     *
     * @return the HTTP client
     */
    static RequestConfig getHttpRequestConfig() {
        return RequestConfig.custom()
        		.setConnectTimeout(getEnvInt("NANOPUB_QUERY_FETCHING_CONNECT_TIMEOUT", 1000))
        		.setConnectionRequestTimeout(getEnvInt("NANOPUB_QUERY_FETCHING_CONNECTION_REQUEST_TIMEOUT", 100))
        		.setSocketTimeout(getEnvInt("NANOPUB_QUERY_FETCHING_SOCKET_TIMEOUT", 1000))
        		.setCookieSpec(CookieSpecs.IGNORE_COOKIES).build();
    }

}

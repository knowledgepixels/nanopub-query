package com.knowledgepixels.query;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

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
import org.nanopub.vocabulary.NPA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.hash.Hashing;

/**
 * Utils class for Nanopub Registry.
 */
public class Utils {

    private Utils() {
    }  // no instances allowed

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    private static final Logger logger = LoggerFactory.getLogger(Utils.class);

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
                query.setBinding("g", NPA.GRAPH);
                query.setBinding("p", NPA.IS_HASH_OF);
                try (TupleQueryResult r = query.evaluate()) {
                    while (r.hasNext()) {
                        BindingSet b = r.next();
                        String hash = b.getBinding("s").getValue().stringValue();
                        hash = StringUtils.replace(hash, NPA.HASH.toString(), "");
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
                conn.add(vf.createStatement(vf.createIRI(NPA.HASH + hash), NPA.IS_HASH_OF, objV, NPA.GRAPH));
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
        String s = getRawEnv(envVarName);
        if (s != null && !s.isEmpty()) {
            return s;
        }
        return defaultValue;
    }

    /**
     * Reads a single environment variable from the JVM's in-memory environment
     * block. The previous implementation used Apache Commons Exec's
     * {@code EnvironmentUtils.getProcEnvironment()}, which forks a subprocess
     * ({@code env}/{@code sh}) and parses its stdout on every call. That is neither
     * thread-safe nor robust under load, and since {@link #getEnvString} is on the
     * per-nanopub hot path ({@link FeatureFlags#fullRepoEnabled()} and friends,
     * called from the 4-thread loading pool) an intermittently mangled read made
     * {@code fullRepoEnabled()} evaluate to {@code false} and silently skip the
     * full-repo write for individual nanopubs — leaving {@code full} behind
     * {@code meta} undetectably (issue #117).
     *
     * <p>Extracted as a package-private seam because {@link System} static methods
     * cannot be mocked directly with Mockito; tests stub this instead.
     *
     * @param envVarName environment variable name
     * @return the raw value, or {@code null} if unset
     */
    static String getRawEnv(String envVarName) {
        return System.getenv(envVarName);
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
            logger.info("Could not get environment variable", ex);
        }
        return defaultValue;
    }

    /**
     * Appends the RDF4J protocol {@code timeout} parameter (in seconds) to a request URI,
     * making the server abort query evaluation after the given time. Without this, a client
     * disconnect (e.g. the public proxy's 10s cut) leaves the server evaluating to completion;
     * enough of those zombie evaluations pinning store snapshots sent the changeset overlay
     * into a self-reinforcing pile-up that saturated every worker thread (incident 2026-08-21).
     * <p>
     * A URI that already carries a {@code timeout} parameter is returned unchanged, so
     * clients can request a shorter (or longer) limit explicitly. A non-positive
     * {@code timeoutSeconds} disables injection.
     *
     * @param uri            the request URI, with or without a query string
     * @param timeoutSeconds server-side evaluation limit in seconds; non-positive = no-op
     * @return the URI with the timeout parameter appended, or unchanged
     */
    public static String appendQueryTimeout(String uri, int timeoutSeconds) {
        if (timeoutSeconds <= 0 || uri == null) return uri;
        int q = uri.indexOf('?');
        if (q >= 0 && uri.substring(q).matches(".*[?&]timeout=.*")) return uri;
        return uri + (q >= 0 ? "&" : "?") + "timeout=" + timeoutSeconds;
    }

    private static String version;

    /**
     * Returns the application version, read from the filtered version.properties resource.
     *
     * @return the project version, or "unknown" if it cannot be read
     */
    public static String getVersion() {
        String v = version;
        if (v != null) {
            return v;
        }
        Properties p = new Properties();
        try (InputStream in = Utils.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException ex) {
            logger.warn("Could not read version.properties", ex);
        }
        v = p.getProperty("version", "unknown");
        version = v;
        return v;
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
                .setConnectTimeout(getEnvInt("NANOPUB_QUERY_FETCHING_CONNECT_TIMEOUT", 10000))
                .setConnectionRequestTimeout(getEnvInt("NANOPUB_QUERY_FETCHING_CONNECTION_REQUEST_TIMEOUT", 1000))
                .setSocketTimeout(getEnvInt("NANOPUB_QUERY_FETCHING_SOCKET_TIMEOUT", 30000))
                .setCookieSpec(CookieSpecs.IGNORE_COOKIES).build();
    }

}

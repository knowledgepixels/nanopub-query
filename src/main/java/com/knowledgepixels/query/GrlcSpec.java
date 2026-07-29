package com.knowledgepixels.query;

import io.vertx.core.MultiMap;
import net.trustyuri.TrustyUriUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.SimpleCreatorPattern;
import org.nanopub.extra.server.GetNanopub;
import org.nanopub.extra.services.QueryTemplate;
import org.nanopub.vocabulary.NPA;
import org.nanopub.vocabulary.NPX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nanopub Query-specific wrapper around {@link QueryTemplate} that adds:
 * <ul>
 *   <li>request-URL parsing ({@code /…/RA…/{name}.rq})</li>
 *   <li>nanopub fetch cache</li>
 *   <li>the {@code _nanopub_trig} inline-nanopub parameter</li>
 *   <li>{@code api-version=latest} resolution against the local meta repo</li>
 *   <li>rewriting the canonical {@code https://w3id.org/np/l/nanopub-query-1.1/repo/}
 *       endpoint prefix to the in-cluster {@code NANOPUB_QUERY_INTERNAL_URL/repo/}, plus
 *       validation that the endpoint matches the canonical prefix</li>
 *   <li>{@link #getSpec()} YAML rendering for the legacy {@code /grlc-spec/} route</li>
 *   <li>{@link #getRepoName()} derived from the rewritten endpoint</li>
 * </ul>
 * <p>Parsing, placeholder extraction and SPARQL expansion are delegated to
 * {@link QueryTemplate}. Static placeholder helpers are forwarded so existing
 * callers ({@link OpenApiSpecPage}) keep compiling.
 */
public class GrlcSpec {

    private static final Logger logger = LoggerFactory.getLogger(GrlcSpec.class);

    private static final ConcurrentHashMap<String, Nanopub> nanopubCache = new ConcurrentHashMap<>();

    /**
     * Exception for invalid grlc specifications.
     */
    public static class InvalidGrlcSpecException extends Exception {

        private InvalidGrlcSpecException(String msg) {
            super(msg);
        }

        private InvalidGrlcSpecException(String msg, Throwable throwable) {
            super(msg, throwable);
        }

    }

    /**
     * Public base URL of this Nanopub Query instance, used only in generated
     * documentation: the grlc spec's query listing here and the OpenAPI server
     * URL in {@link OpenApiSpecPage}. Never used for query execution — that is
     * {@link #nanopubQueryInternalUrl}'s job.
     */
    public static final String nanopubQueryUrl = Utils.getEnvString("NANOPUB_QUERY_URL", "http://query:9393/");

    /**
     * In-cluster base URL of this Nanopub Query instance, used to rewrite the
     * canonical {@code https://w3id.org/np/l/nanopub-query-1.1/repo/} prefix in
     * query endpoints and SPARQL {@code SERVICE} clauses. These URLs are
     * resolved by the backend RDF4J server when it evaluates the federated
     * query, so they must stay inside the cluster: the default resolves to this
     * app's docker-compose service address, whose {@code /repo/*} proxy forwards
     * to the RDF4J server directly. Routing this traffic through the public
     * edge instead (as happened when it shared {@code NANOPUB_QUERY_URL}) makes
     * every federated {@code /api} call compete with external clients for
     * nginx's per-repo connection limits and adds TLS/proxy overhead on the
     * hottest repos (issues #142, incident 2026-07-28). Only set
     * {@code NANOPUB_QUERY_INTERNAL_URL} if your deployment reaches the app
     * under a different in-cluster address.
     */
    public static final String nanopubQueryInternalUrl = Utils.getEnvString("NANOPUB_QUERY_INTERNAL_URL", "http://query:9393/");

    private static final String NANOPUB_QUERY_REPO_URL = "https://w3id.org/np/l/nanopub-query-1.1/repo/";

    private final MultiMap parameters;
    private final QueryTemplate template;
    private final String requestUrlBase;
    private final String artifactCode;
    private final String queryPart;
    private final String queryContent;
    private final String endpoint;

    /**
     * Creates a new page instance.
     *
     * @param requestUrl The request URL
     * @param parameters The URL request parameters
     */
    public GrlcSpec(String requestUrl, MultiMap parameters) throws InvalidGrlcSpecException {
        this.parameters = parameters;
        requestUrl = requestUrl.replaceFirst("\\?.*$", "");
        if (!requestUrl.matches(".*/RA[A-Za-z0-9\\-_]{43}/(.*)?")) {
            throw new InvalidGrlcSpecException("Invalid grlc API request: " + requestUrl);
        }
        String parsedArtifactCode = requestUrl.replaceFirst("^(.*/)(RA[A-Za-z0-9\\-_]{43})/(.*)?$", "$2");
        requestUrlBase = requestUrl.replaceFirst("^/(.*/)(RA[A-Za-z0-9\\-_]{43})/(.*)?$", "$1");

        String parsedQueryPart = requestUrl.replaceFirst("^(.*/)(RA[A-Za-z0-9\\-_]{43}/)(.*)?$", "$3");
        parsedQueryPart = parsedQueryPart.replaceFirst(".rq$", "");
        queryPart = parsedQueryPart;

        Nanopub np;
        String nanopubParam = parameters.get("_nanopub_trig");
        if (nanopubParam != null && !nanopubParam.isEmpty()) {
            try {
                byte[] trig = Base64.getUrlDecoder().decode(nanopubParam);
                np = new NanopubImpl(new ByteArrayInputStream(trig), RDFFormat.TRIG);
            } catch (MalformedNanopubException | IOException | IllegalArgumentException ex) {
                throw new InvalidGrlcSpecException("Failed to parse nanopub from 'nanopub' parameter", ex);
            }
        } else {
            np = nanopubCache.computeIfAbsent(parsedArtifactCode, GetNanopub::get);
        }
        // TODO rename "api-version" to "_api_version" for consistency
        if (parameters.get("api-version") != null && parameters.get("api-version").equals("latest")) {
            String latestUri = getLatestVersionIdLocally(np.getUri().stringValue());
            if (!latestUri.equals(np.getUri().stringValue())) {
                np = nanopubCache.computeIfAbsent(TrustyUriUtils.getArtifactCode(latestUri), GetNanopub::get);
            }
            parsedArtifactCode = TrustyUriUtils.getArtifactCode(np.getUri().stringValue());
        }
        artifactCode = parsedArtifactCode;

        try {
            if (queryPart.isEmpty()) {
                template = new QueryTemplate(np);
            } else {
                template = new QueryTemplate(np, artifactCode + "/" + queryPart);
            }
        } catch (IllegalArgumentException ex) {
            throw new InvalidGrlcSpecException(ex.getMessage(), ex);
        }

        if (!queryPart.isEmpty() && !queryPart.equals(template.getQuerySuffix())) {
            throw new InvalidGrlcSpecException(
                    "Query part doesn't match query name: " + queryPart + " / " + template.getQuerySuffix());
        }

        queryContent = template.getSparql().replace(NANOPUB_QUERY_REPO_URL, nanopubQueryInternalUrl + "repo/");

        IRI rawEndpoint = template.getEndpoint();
        if (rawEndpoint != null) {
            String ep = rawEndpoint.stringValue();
            if (!ep.startsWith(NANOPUB_QUERY_REPO_URL)) {
                throw new InvalidGrlcSpecException("Invalid/non-recognized endpoint: " + ep);
            }
            endpoint = ep.replace(NANOPUB_QUERY_REPO_URL, nanopubQueryInternalUrl + "repo/");
        } else {
            endpoint = null;
        }
    }

    /**
     * Returns the grlc spec as a string.
     *
     * @return grlc specification string
     */
    public String getSpec() {
        String s = "";
        String label = template.getLabel();
        String desc = template.getDescription();
        IRI license = template.getLicense();
        String queryName = template.getQuerySuffix();
        if (queryPart.isEmpty()) {
            if (label == null) {
                s += "title: \"untitled query\"\n";
            } else {
                s += "title: \"" + QueryTemplate.escapeLiteral(label) + "\"\n";
            }
            s += "description: \"" + QueryTemplate.escapeLiteral(desc) + "\"\n";
            StringBuilder userName = new StringBuilder();
            Set<IRI> creators = SimpleCreatorPattern.getCreators(template.getNanopub());
            for (IRI userIri : creators) {
                userName.append(", ").append(userIri);
            }
            if (!userName.isEmpty()) {
                userName = new StringBuilder(userName.substring(2));
            }
            String url = "";
            if (!creators.isEmpty()) {
                url = creators.iterator().next().stringValue();
            }
            s += "contact:\n";
            s += "  name: \"" + QueryTemplate.escapeLiteral(userName.toString()) + "\"\n";
            s += "  url: " + url + "\n";
            if (license != null) {
                s += "licence: " + license.stringValue() + "\n";
            }
            s += "queries:\n";
            s += "  - " + nanopubQueryUrl + requestUrlBase + artifactCode + "/" + queryName + ".rq";
        } else if (queryPart.equals(queryName)) {
            if (label != null) {
                s += "#+ summary: \"" + QueryTemplate.escapeLiteral(label) + "\"\n";
            }
            if (desc != null) {
                s += "#+ description: \"" + QueryTemplate.escapeLiteral(desc) + "\"\n";
            }
            if (license != null) {
                s += "#+ licence: " + license.stringValue() + "\n";
            }
            if (endpoint != null) {
                s += "#+ endpoint: " + endpoint + "\n";
            }
            s += "\n";
            s += queryContent;
        } else {
            throw new RuntimeException("Unexpected queryPart: " + queryPart);
        }
        return s;
    }

    /**
     * Returns the request parameters.
     *
     * @return the request parameters
     */
    public MultiMap getParameters() {
        return parameters;
    }

    /**
     * Returns the nanopub.
     *
     * @return the nanopub
     */
    public Nanopub getNanopub() {
        return template.getNanopub();
    }

    /**
     * Returns the artifact code.
     *
     * @return the artifact code
     */
    public String getArtifactCode() {
        return artifactCode;
    }

    /**
     * Returns the label.
     *
     * @return the label
     */
    public String getLabel() {
        return template.getLabel();
    }

    /**
     * Returns the description.
     *
     * @return the description
     */
    public String getDescription() {
        return template.getDescription();
    }

    /**
     * Returns the query name.
     *
     * @return the query name
     */
    public String getQueryName() {
        return template.getQuerySuffix();
    }

    /**
     * Returns the list of placeholders.
     *
     * @return the list of placeholders
     */
    public List<String> getPlaceholdersList() {
        return template.getPlaceholdersList();
    }

    /**
     * Returns the repository name derived from the endpoint URL.
     *
     * @return the repository name
     */
    public String getRepoName() {
        return endpoint.replaceAll("/", "_").replaceFirst("^.*_repo_", "");
    }

    /**
     * Returns the query content (with the canonical repo URL rewritten to the
     * in-cluster {@link #nanopubQueryInternalUrl}{@code /repo/}).
     *
     * @return the query content
     */
    public String getQueryContent() {
        return queryContent;
    }

    public boolean isConstructQuery() {
        return template.isConstructQuery();
    }

    /**
     * Expands the query by replacing the placeholders with the provided parameter
     * values, and rewrites the canonical repo URL to the in-cluster one.
     *
     * @return the expanded query
     * @throws InvalidGrlcSpecException if a non-optional placeholder is missing a value
     */
    public String expandQuery() throws InvalidGrlcSpecException {
        Map<String, List<String>> params = new LinkedHashMap<>();
        for (String name : parameters.names()) {
            params.put(name, new ArrayList<>(parameters.getAll(name)));
        }
        logger.info("Expanding grlc query with parameters: {}", parameters);
        try {
            String expanded = template.expandQuery(params);
            return expanded.replace(NANOPUB_QUERY_REPO_URL, nanopubQueryInternalUrl + "repo/");
        } catch (IllegalArgumentException ex) {
            throw new InvalidGrlcSpecException(ex.getMessage(), ex);
        }
    }

    /**
     * Escapes a literal string for SPARQL.
     *
     * @param s The string
     * @return The escaped string
     */
    public static String escapeLiteral(String s) {
        return QueryTemplate.escapeLiteral(s);
    }

    /**
     * Checks whether the given placeholder is an optional placeholder.
     *
     * @param placeholder The placeholder name
     * @return true if it is an optional placeholder, false otherwise
     */
    public static boolean isOptionalPlaceholder(String placeholder) {
        return QueryTemplate.isOptionalPlaceholder(placeholder);
    }

    /**
     * Checks whether the given placeholder is a multi-value placeholder.
     *
     * @param placeholder The placeholder name
     * @return true if it is a multi-value placeholder, false otherwise
     */
    public static boolean isMultiPlaceholder(String placeholder) {
        return QueryTemplate.isMultiPlaceholder(placeholder);
    }

    /**
     * Checks whether the given placeholder is an IRI placeholder.
     *
     * @param placeholder The placeholder name
     * @return true if it is an IRI placeholder, false otherwise
     */
    public static boolean isIriPlaceholder(String placeholder) {
        return QueryTemplate.isIriPlaceholder(placeholder);
    }

    /**
     * Returns the parameter name for the given placeholder.
     *
     * @param placeholder The placeholder name
     * @return The parameter name
     */
    public static String getParamName(String placeholder) {
        return QueryTemplate.getParamName(placeholder);
    }

    /**
     * Serializes an IRI string for SPARQL.
     *
     * @param iriString The IRI string
     * @return The serialized IRI
     */
    public static String serializeIri(String iriString) {
        return QueryTemplate.serializeIri(iriString);
    }

    /**
     * Serializes a literal string for SPARQL.
     *
     * @param literalString The literal string
     * @return The serialized literal
     */
    public static String serializeLiteral(String literalString) {
        return QueryTemplate.serializeLiteral(literalString);
    }

    /**
     * Resolves the latest version of a nanopub by following the supersedes chain in the local store.
     * Uses a single SPARQL query with a property path to find the latest non-invalidated version
     * signed by the same key. If no result is found locally, or if the local store is unavailable,
     * returns the original URI.
     *
     * @param nanopubUri the URI of the nanopub to resolve
     * @return the URI of the latest version
     */
    static String getLatestVersionIdLocally(String nanopubUri) {
        logger.info("Resolving latest version locally for: {}", nanopubUri);
        try {
            RepositoryConnection conn = TripleStore.get().getRepoConnection("meta");
            try (conn) {
                String query =
                        "SELECT ?latest ?date WHERE { " +
                        "GRAPH <" + NPA.GRAPH + "> { " +
                        "<" + nanopubUri + "> <" + NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY + "> ?pubkey . " +
                        "?latest <" + NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY + "> ?pubkey . " +
                        "FILTER NOT EXISTS { ?npx <" + NPX.INVALIDATES + "> ?latest ; " +
                        "<" + NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY + "> ?pubkey . } " +
                        "?latest <" + DCTERMS.CREATED + "> ?date . " +
                        "} " +
                        "GRAPH <" + NPA.NETWORK_GRAPH + "> { " +
                        "?latest (<" + NPX.SUPERSEDES + ">)* <" + nanopubUri + "> . " +
                        "} " +
                        "} ORDER BY DESC(?date) LIMIT 1";
                TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate();
                try (r) {
                    if (r.hasNext()) {
                        String latestUri = r.next().getBinding("latest").getValue().stringValue();
                        logger.info("Resolved latest version: {}", latestUri);
                        return latestUri;
                    }
                }
                logger.info("No latest version found locally for: {}", nanopubUri);
                return nanopubUri;
            }
        } catch (Exception ex) {
            logger.warn("Could not resolve latest version locally, using original version: {}", ex.getMessage());
            return nanopubUri;
        }
    }

}

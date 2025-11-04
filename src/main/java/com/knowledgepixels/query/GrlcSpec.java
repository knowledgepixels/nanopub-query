package com.knowledgepixels.query;

import io.vertx.core.MultiMap;
import net.trustyuri.TrustyUriUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractSimpleQueryModelVisitor;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.nanopub.Nanopub;
import org.nanopub.SimpleCreatorPattern;
import org.nanopub.extra.server.GetNanopub;
import org.nanopub.extra.services.QueryAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

//TODO merge this class with GrlcQuery of Nanodash and move to a library like nanopub-java

/**
 * This class produces a page with the grlc specification. This is needed internally to tell grlc
 * how to execute a particular query template.
 */
public class GrlcSpec {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    private static final Logger log = LoggerFactory.getLogger(GrlcSpec.class);

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
     * IRI for relation to link a grlc query instance to its SPARQL template.
     */
    public static final IRI HAS_SPARQL = vf.createIRI("https://w3id.org/kpxl/grlc/sparql");

    /**
     * IRI for relation to link a grlc query instance to its SPARQL endpoint URL.
     */
    public static final IRI HAS_ENDPOINT = vf.createIRI("https://w3id.org/kpxl/grlc/endpoint");

    /**
     * URL for the given Nanopub Query instance, needed for internal coordination.
     */
    public static final String nanopubQueryUrl = Utils.getEnvString("NANOPUB_QUERY_URL", "http://query:9393/");

    private MultiMap parameters;
    private Nanopub np;
    private String requestUrlBase;
    private String artifactCode;
    private String queryPart;
    private String queryName;
    private String label;
    private String desc;
    private String license;
    private String queryContent;
    private String endpoint;
    private List<String> placeholdersList;

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
        artifactCode = requestUrl.replaceFirst("^(.*/)(RA[A-Za-z0-9\\-_]{43})/(.*)?$", "$2");
        requestUrlBase = requestUrl.replaceFirst("^/(.*/)(RA[A-Za-z0-9\\-_]{43})/(.*)?$", "$1");

        queryPart = requestUrl.replaceFirst("^(.*/)(RA[A-Za-z0-9\\-_]{43}/)(.*)?$", "$3");
        queryPart = queryPart.replaceFirst(".rq$", "");

        // TODO Get the nanopub from the local store:
        np = GetNanopub.get(artifactCode);
        if (parameters.get("api-version") != null && parameters.get("api-version").equals("latest")) {
            // TODO Get the latest version from the local store:
            np = GetNanopub.get(QueryAccess.getLatestVersionId(np.getUri().stringValue()));
            artifactCode = TrustyUriUtils.getArtifactCode(np.getUri().stringValue());
        }
        for (Statement st : np.getAssertion()) {
            if (!st.getSubject().stringValue().startsWith(np.getUri().stringValue())) continue;
            String qn = st.getSubject().stringValue().replaceFirst("^.*[#/](.*)$", "$1");
            if (queryName != null && !qn.equals(queryName)) {
                throw new InvalidGrlcSpecException("Subject suffixes don't match: " + queryName);
            }
            queryName = qn;
            if (st.getPredicate().equals(RDFS.LABEL)) {
                label = st.getObject().stringValue();
            } else if (st.getPredicate().equals(DCTERMS.DESCRIPTION)) {
                desc = st.getObject().stringValue();
            } else if (st.getPredicate().equals(DCTERMS.LICENSE) && st.getObject() instanceof IRI) {
                license = st.getObject().stringValue();
            } else if (st.getPredicate().equals(HAS_SPARQL)) {
                // TODO Improve this:
                queryContent = st.getObject().stringValue().replace("https://w3id.org/np/l/nanopub-query-1.1/repo/", nanopubQueryUrl + "repo/");
            } else if (st.getPredicate().equals(HAS_ENDPOINT) && st.getObject() instanceof IRI) {
                endpoint = st.getObject().stringValue();
                if (endpoint.startsWith("https://w3id.org/np/l/nanopub-query-1.1/repo/")) {
                    endpoint = endpoint.replace("https://w3id.org/np/l/nanopub-query-1.1/repo/", nanopubQueryUrl + "repo/");
                } else {
                    throw new InvalidGrlcSpecException("Invalid/non-recognized endpoint: " + endpoint);
                }
            }
        }

        if (!queryPart.isEmpty() && !queryPart.equals(queryName)) {
            throw new InvalidGrlcSpecException("Query part doesn't match query name: " + queryPart + " / " + queryName);
        }

        final Set<String> placeholders = new HashSet<>();
        try {
            ParsedQuery query = new SPARQLParser().parseQuery(queryContent, null);
            query.getTupleExpr().visitChildren(new AbstractSimpleQueryModelVisitor<>() {

                @Override
                public void meet(Var node) throws RuntimeException {
                    super.meet(node);
                    if (!node.isConstant() && !node.isAnonymous() && node.getName().startsWith("_")) {
                        placeholders.add(node.getName());
                    }
                }

            });
        } catch (MalformedQueryException ex) {
            throw new InvalidGrlcSpecException("Invalid SPARQL string", ex);
        }
        List<String> placeholdersListPre = new ArrayList<>(placeholders);
        Collections.sort(placeholdersListPre);
        placeholdersListPre.sort(Comparator.comparing(String::length));
        placeholdersList = Collections.unmodifiableList(placeholdersListPre);
    }

    /**
     * Returns the grlc spec as a string.
     *
     * @return grlc specification string
     */
    public String getSpec() {
        String s = "";
        if (queryPart.isEmpty()) {
            if (label == null) {
                s += "title: \"untitled query\"\n";
            } else {
                s += "title: \"" + escapeLiteral(label) + "\"\n";
            }
            s += "description: \"" + escapeLiteral(desc) + "\"\n";
            StringBuilder userName = new StringBuilder();
            Set<IRI> creators = SimpleCreatorPattern.getCreators(np);
            for (IRI userIri : creators) {
                userName.append(", ").append(userIri);
            }
            if (!userName.isEmpty()) userName = new StringBuilder(userName.substring(2));
            String url = "";
            if (!creators.isEmpty()) url = creators.iterator().next().stringValue();
            s += "contact:\n";
            s += "  name: \"" + escapeLiteral(userName.toString()) + "\"\n";
            s += "  url: " + url + "\n";
            if (license != null) {
                s += "licence: " + license + "\n";
            }
            s += "queries:\n";
            s += "  - " + nanopubQueryUrl + requestUrlBase + artifactCode + "/" + queryName + ".rq";
        } else if (queryPart.equals(queryName)) {
            if (label != null) {
                s += "#+ summary: \"" + escapeLiteral(label) + "\"\n";
            }
            if (desc != null) {
                s += "#+ description: \"" + escapeLiteral(desc) + "\"\n";
            }
            if (license != null) {
                s += "#+ licence: " + license + "\n";
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
        return np;
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
        return label;
    }

    /**
     * Returns the description.
     *
     * @return the description
     */
    public String getDescription() {
        return desc;
    }

    /**
     * Returns the query name.
     *
     * @return the query name
     */
    public String getQueryName() {
        return queryName;
    }

    public List<String> getPlaceholdersList() {
        return placeholdersList;
    }

    public String getRepoName() {
        return endpoint.replaceAll("/", "_").replaceFirst("^.*_repo_", "");
    }

    public String getQueryContent() {
        return queryContent;
    }

    public String expandQuery() throws InvalidGrlcSpecException {
        String expandedQueryContent = queryContent;
        for (String ph : placeholdersList) {
            log.info("ph: {}", ph);
            log.info("getParamName(ph): {}", getParamName(ph));
            if (isMultiPlaceholder(ph)) {
                // TODO multi placeholders need proper documentation
                List<String> val = parameters.getAll(getParamName(ph));
                if (!isOptionalPlaceholder(ph) && val.isEmpty()) {
                    throw new InvalidGrlcSpecException("Missing value for non-optional placeholder: " + ph);
                }
                if (val.isEmpty()) {
                    expandedQueryContent = expandedQueryContent.replaceAll("values\\s*\\?" + ph + "\\s*\\{\\s*\\}(\\s*\\.)?", "");
                    continue;
                }
                String valueList = "";
                for (String v : val) {
                    if (isIriPlaceholder(ph)) {
                        valueList += serializeIri(v) + " ";
                    } else {
                        valueList += serializeLiteral(v) + " ";
                    }
                }
                expandedQueryContent = expandedQueryContent.replaceAll("values\\s*\\?" + ph + "\\s*\\{\\s*\\}", "values ?" + ph + " { " + escapeSlashes(valueList) + "}");
            } else {
                String val = parameters.get(getParamName(ph));
                log.info("val: {}", val);
                if (!isOptionalPlaceholder(ph) && val == null) {
                    throw new InvalidGrlcSpecException("Missing value for non-optional placeholder: " + ph);
                }
                if (val == null) continue;
                if (isIriPlaceholder(ph)) {
                    expandedQueryContent = expandedQueryContent.replaceAll("\\?" + ph, escapeSlashes(serializeIri(val)));
                } else {
                    expandedQueryContent = expandedQueryContent.replaceAll("\\?" + ph, escapeSlashes(serializeLiteral(val)));
                }
            }
        }
        log.info("Expanded grlc query:\n {}", expandedQueryContent);
        return expandedQueryContent;
    }

    /**
     * Escapes a literal string for SPARQL.
     *
     * @param s The string
     * @return The escaped string
     */
    public static String escapeLiteral(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }

    /**
     * Checks whether the given placeholder is an optional placeholder.
     *
     * @param placeholder The placeholder name
     * @return true if it is an optional placeholder, false otherwise
     */
    public static boolean isOptionalPlaceholder(String placeholder) {
        return placeholder.startsWith("__");
    }

    /**
     * Checks whether the given placeholder is a multi-value placeholder.
     *
     * @param placeholder The placeholder name
     * @return true if it is a multi-value placeholder, false otherwise
     */
    public static boolean isMultiPlaceholder(String placeholder) {
        return placeholder.endsWith("_multi") || placeholder.endsWith("_multi_iri");
    }

    /**
     * Checks whether the given placeholder is an IRI placeholder.
     *
     * @param placeholder The placeholder name
     * @return true if it is an IRI placeholder, false otherwise
     */
    public static boolean isIriPlaceholder(String placeholder) {
        return placeholder.endsWith("_iri");
    }

    /**
     * Returns the parameter name for the given placeholder.
     *
     * @param placeholder The placeholder name
     * @return The parameter name
     */
    public static String getParamName(String placeholder) {
        return placeholder.replaceFirst("^_+", "").replaceFirst("_iri$", "").replaceFirst("_multi$", "");
    }

    /**
     * Serializes an IRI string for SPARQL.
     *
     * @param iriString The IRI string
     * @return The serialized IRI
     */
    public static String serializeIri(String iriString) {
        return "<" + iriString + ">";
    }

    /**
     * Escapes slashes in a string.
     *
     * @param string The string
     * @return The escaped string
     */
    private static String escapeSlashes(String string) {
        return string.replace("\\", "\\\\");
    }

    /**
     * Serializes a literal string for SPARQL.
     *
     * @param literalString The literal string
     * @return The serialized literal
     */
    public static String serializeLiteral(String literalString) {
        return "\"" + escapeLiteral(literalString) + "\"";
    }

}

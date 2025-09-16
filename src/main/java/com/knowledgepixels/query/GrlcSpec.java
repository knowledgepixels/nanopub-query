package com.knowledgepixels.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractSimpleQueryModelVisitor;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.nanopub.Nanopub;
import org.nanopub.SimpleCreatorPattern;
import org.nanopub.extra.server.GetNanopub;
import org.nanopub.extra.services.QueryAccess;

import io.vertx.core.MultiMap;
import net.trustyuri.TrustyUriUtils;

/**
 * This class produces a page with the grlc specification. This is needed internally to tell grlc
 * how to execute a particular query template.
 */
public class GrlcSpec {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

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
    private String artifactCode, queryPart;
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
    public GrlcSpec(String requestUrl, MultiMap parameters) {
        requestUrl = requestUrl.replaceFirst("\\?.*$", "");
        this.parameters = parameters;
        if (!requestUrl.matches(".*/RA[A-Za-z0-9\\-_]{43}/(.*)?")) return;
        artifactCode = requestUrl.replaceFirst("^(.*/)(RA[A-Za-z0-9\\-_]{43})/(.*)?$", "$2");
        queryPart = requestUrl.replaceFirst("^(.*/)(RA[A-Za-z0-9\\-_]{43}/)(.*)?$", "$3");
        requestUrlBase = requestUrl.replaceFirst("^/(.*/)(RA[A-Za-z0-9\\-_]{43})/(.*)?$", "$1");

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
                np = null;
                break;
            }
            queryName = qn;
            if (st.getPredicate().equals(RDFS.LABEL)) {
                label = st.getObject().stringValue();
            } else if (st.getPredicate().equals(DCTERMS.DESCRIPTION)) {
                desc = st.getObject().stringValue();
            } else if (st.getPredicate().equals(DCTERMS.LICENSE) && st.getObject() instanceof IRI) {
                license = st.getObject().stringValue();
            } else if (st.getPredicate().equals(HAS_SPARQL)) {
                queryContent = st.getObject().stringValue().replace("https://w3id.org/np/l/nanopub-query-1.1/", nanopubQueryUrl);
            } else if (st.getPredicate().equals(HAS_ENDPOINT) && st.getObject() instanceof IRI) {
                endpoint = st.getObject().stringValue();
                if (endpoint.startsWith("https://w3id.org/np/l/nanopub-query-1.1/")) {
                    endpoint = endpoint.replace("https://w3id.org/np/l/nanopub-query-1.1/", nanopubQueryUrl);
                }
            }
        }

        final Set<String> placeholders = new HashSet<>();
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
        if (np == null) return null;
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
        } else if (queryPart.equals(queryName + ".rq")) {
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
            return null;
        }
        return s;
    }

    public String getRepoName() {
        return endpoint.replaceAll("/", "_").replaceFirst("^.*_repo_", "");
    }

    public String getQueryContent() {
        return queryContent;
    }

    public String getExpandedQueryContent() {
        String expanded = queryContent;
        for (String ph : placeholdersList) {
            System.err.println("ph: " + ph);
            System.err.println("getParamName(ph): " + getParamName(ph));
            String val = parameters.get(getParamName(ph));
            System.err.println("val: " + val);
            if (!isOptionalPlaceholder(ph) && val == null) {
                // TODO throw exception
                return null;
            }
            if (val == null) continue;
            if (isIriPlaceholder(ph)) {
                expanded = expanded.replaceAll("\\?" + ph, "<" + val + ">");
            } else {
                expanded = expanded.replaceAll("\\?" + ph, "\"" + escapeLiteral(val) + "\"");
            }
        }
        System.err.println("expanded: " + expanded);
        return expanded;
    }

    public static String escapeLiteral(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }

    public static boolean isOptionalPlaceholder(String placeholder) {
        return placeholder.startsWith("__");
    }

    public static boolean isIriPlaceholder(String placeholder) {
        return placeholder.endsWith("_iri");
    }

    public static String getParamName(String placeholder) {
        return placeholder.replaceFirst("^_+", "").replaceFirst("_iri$", "");
    }

}

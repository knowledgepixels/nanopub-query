package com.knowledgepixels.query;

import io.vertx.core.MultiMap;
import net.trustyuri.TrustyUriUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractSimpleQueryModelVisitor;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.nanopub.Nanopub;
import org.nanopub.extra.server.GetNanopub;
import org.nanopub.extra.services.QueryAccess;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.*;

/**
 * Page for Open API compliant specification.
 */
public class OpenApiSpecPage {

    private Map<String, Object> dataMap = new LinkedHashMap<>();
    private Nanopub np;

    /**
     * Creates a new page instance.
     *
     * @param requestUrl The request URL
     * @param parameters The URL request parameters
     */
    public OpenApiSpecPage(String requestUrl, MultiMap parameters) {
        requestUrl = requestUrl.replaceFirst("\\?.*$", "");
        if (!requestUrl.matches(".*/RA[A-Za-z0-9\\-_]{43}/(.*)?")) return;
        String artifactCode = requestUrl.replaceFirst("^(.*/)(RA[A-Za-z0-9\\-_]{43})/(.*)?$", "$2");
//		String queryPart = requestUrl.replaceFirst("^(.*/)(RA[A-Za-z0-9\\-_]{43}/)(.*)?$", "$3");
//		String requestUrlBase = requestUrl.replaceFirst("^/(.*/)(RA[A-Za-z0-9\\-_]{43})/(.*)?$", "$1");

        // TODO Get the nanopub from the local store:
        np = GetNanopub.get(artifactCode);
        if (parameters.get("api-version") != null && parameters.get("api-version").equals("latest")) {
            // TODO Get the latest version from the local store:
            np = GetNanopub.get(QueryAccess.getLatestVersionId(np.getUri().stringValue()));
            artifactCode = TrustyUriUtils.getArtifactCode(np.getUri().stringValue());
        }
        String queryName = null;
        String label = null;
        String desc = null;
        String queryContent = null;
        String endpoint = null;
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
//			} else if (st.getPredicate().equals(DCTERMS.LICENSE) && st.getObject() instanceof IRI) {
//				license = st.getObject().stringValue();
            } else if (st.getPredicate().equals(GrlcSpec.HAS_SPARQL)) {
                queryContent = st.getObject().stringValue().replace("https://w3id.org/np/l/nanopub-query-1.1/", GrlcSpec.nanopubQueryUrl);
            } else if (st.getPredicate().equals(GrlcSpec.HAS_ENDPOINT) && st.getObject() instanceof IRI) {
                endpoint = st.getObject().stringValue();
                if (endpoint.startsWith("https://w3id.org/np/l/nanopub-query-1.1/")) {
                    endpoint = endpoint.replace("https://w3id.org/np/l/nanopub-query-1.1/", GrlcSpec.nanopubQueryUrl);
                }
            }
        }

        // TODO Code duplicated from com.knowledgepixels.nanodash.GrlcQuery:
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
        List<String> placeholdersList = new ArrayList<>(placeholders);
        Collections.sort(placeholdersList);

        dataMap.put("openapi", "3.0.4");

        Map<String, Object> infoMap = new LinkedHashMap<>();
        infoMap.put("title", label);
        infoMap.put("description", "API definition source: <a target=\"_blank\" href=\"" + np.getUri() + "\"><svg height=\"0.8em\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 8 8\"><path d=\"M5,8H8L3,0H0M8,4.8V0H5M0,3.2V8H3\"/></svg> " + artifactCode.substring(0, 10) + "</a>");
        infoMap.put("version", artifactCode.substring(0, 10));
        dataMap.put("info", infoMap);

        List<Object> serversList = new ArrayList<>();
        Map<String, Object> serverMap = new LinkedHashMap<>();
        serverMap.put("url", Utils.getEnvString("NANOPUB_QUERY_URL", "http://localhost:9393/") + "api/" + artifactCode);
        serverMap.put("description", "This Nanopub Query instance");
        serversList.add(serverMap);
        dataMap.put("servers", serversList);

        Map<String, Object> pathsMap = new LinkedHashMap<>();
        Map<String, Object> rootPathMap = new LinkedHashMap<>();
        Map<String, Object> getOpMap = new LinkedHashMap<>();
        //getOpMap.put("summary", label);
        getOpMap.put("description", desc);
        Map<String, Object> responsesMap = new LinkedHashMap<>();
        Map<String, Object> successrespMap = new LinkedHashMap<>();
        Map<String, Object> contentMap = new LinkedHashMap<>();
        contentMap.put("text/csv", new HashMap<>());
        contentMap.put("application/json", new HashMap<>());
        successrespMap.put("content", contentMap);
        successrespMap.put("description", "result table");
        responsesMap.put("200", successrespMap);
        List<Object> parametersList = new ArrayList<>();
        for (String p : placeholdersList) {
            Map<String, Object> paramMap = new LinkedHashMap<>();
            paramMap.put("in", "query");
            String name = p.replaceFirst("^_*", "").replaceFirst("_iri$", "");
            paramMap.put("name", name);
            paramMap.put("required", !p.startsWith("__"));
            Map<String, Object> stringType = new LinkedHashMap<>();
            stringType.put("type", "string");
            paramMap.put("schema", stringType);
            parametersList.add(paramMap);
        }
        getOpMap.put("parameters", parametersList);
        getOpMap.put("responses", responsesMap);
        rootPathMap.put("get", getOpMap);
        pathsMap.put("/" + queryName, rootPathMap);
        dataMap.put("paths", pathsMap);
    }

    /**
     * Returns the Open API spec as a string.
     *
     * @return Open API specification string
     */
    public String getSpec() {
        if (np == null) return null;
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return new Yaml(options).dump(dataMap);
    }

}

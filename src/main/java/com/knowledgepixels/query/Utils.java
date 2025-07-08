package com.knowledgepixels.query;

import com.google.common.hash.Hashing;
import org.apache.commons.exec.environment.EnvironmentUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Utils {

    private Utils() {}  // no instances allowed

    private static ValueFactory vf = SimpleValueFactory.getInstance();

    public static final IRI IS_HASH_OF = vf.createIRI("http://purl.org/nanopub/admin/isHashOf");
    public static final IRI HASH_PREFIX = vf.createIRI("http://purl.org/nanopub/admin/hash/");

    private static Map<String, Value> hashToObjMap;

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

    public static Value getObjectForHash(String hash) {
        return getHashToObjectMap().get(hash);
    }

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

    static Value getValue(Object obj) {
        if (obj instanceof Value) {
            return (Value) obj;
        } else {
            return vf.createLiteral(obj.toString());
        }
    }

    public static String getShortPubkeyName(String pubkey) {
        return pubkey.replaceFirst("^(.).{39}(.{5}).*$", "$1..$2..");
    }

    public static Value getObjectForPattern(RepositoryConnection conn, IRI graph, IRI subj, IRI pred) {
        TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * { graph <" + graph.stringValue() + "> { <" + subj.stringValue() + "> <" + pred.stringValue() + "> ?o } }").evaluate();
        try (r) {
            if (!r.hasNext()) return null;
            return r.next().getBinding("o").getValue();
        }
    }

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

    public static String getEnvString(String envVarName, String defaultValue) {
        try {
            String s = EnvironmentUtils.getProcEnvironment().get(envVarName);
            if (s != null && !s.isEmpty()) return s;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return defaultValue;
    }

    public static int getEnvInt(String envVarName, int defaultValue) {
        try {
            String s = getEnvString(envVarName, null);
            if (s != null && !s.isEmpty()) return Integer.parseInt(s);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return defaultValue;
    }

    public static final String defaultQuery =
            "prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
                    + "prefix dct: <http://purl.org/dc/terms/>\n"
                    + "prefix np: <http://www.nanopub.org/nschema#>\n"
                    + "prefix npa: <http://purl.org/nanopub/admin/>\n"
                    + "prefix npx: <http://purl.org/nanopub/x/>\n"
                    + "\n"
                    + "select * where {\n"
                    + "## Info about this repo:\n"
                    + "  npa:thisRepo ?pred ?obj .\n"
                    + "## Search for nanopublications:\n"
                    + "# graph npa:graph {\n"
                    + "#   ?np npa:hasValidSignatureForPublicKey ?pubkey .\n"
                    + "#   filter not exists { ?npx npx:invalidates ?np ; npa:hasValidSignatureForPublicKey ?pubkey . }\n"
                    + "#   ?np dct:created ?date .\n"
                    + "#   ?np np:hasAssertion ?a .\n"
                    + "#   optional { ?np rdfs:label ?label }\n"
                    + "# }\n"
                    + "} limit 10";

}

package com.knowledgepixels.query;

import com.knowledgepixels.query.vocabulary.SpacesVocab;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.nanopub.vocabulary.NPA;

import java.util.ArrayList;
import java.util.List;

/**
 * Backs the {@code GET /spaces} listing. Pure rendering helpers are
 * static + side-effect-free so they can be unit-tested without RDF4J;
 * the only repo-touching method is {@link #fetchRows()}.
 */
final class SpacesListingRoute {

    private SpacesListingRoute() {}

    /**
     * One row per (SpaceRef, spaceIri, rootNanopub) tuple in
     * {@code npa:spacesGraph}. The same Space IRI may appear under multiple
     * SpaceRefs during the back-compat phase (one per declaration without
     * {@code gen:hasRootDefinition}).
     */
    record Row(String spaceIri, String spaceRef, String rootNanopub) {}

    private static final String QUERY = ""
            + "PREFIX npa: <" + NPA.NAMESPACE + ">\n"
            + "SELECT ?spaceIri ?spaceRef ?rootNanopub WHERE {\n"
            + "  GRAPH npa:spacesGraph {\n"
            + "    ?spaceRef a npa:SpaceRef ;\n"
            + "              npa:spaceIri ?spaceIri ;\n"
            + "              npa:rootNanopub ?rootNanopub .\n"
            + "  }\n"
            + "} ORDER BY ?spaceIri ?spaceRef";

    static List<Row> fetchRows() {
        List<Row> rows = new ArrayList<>();
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection("spaces");
             TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, QUERY).evaluate()) {
            while (r.hasNext()) {
                BindingSet b = r.next();
                rows.add(new Row(
                        b.getBinding("spaceIri").getValue().stringValue(),
                        b.getBinding("spaceRef").getValue().stringValue(),
                        b.getBinding("rootNanopub").getValue().stringValue()));
            }
        }
        return rows;
    }

    static String renderHtml(List<Row> rows) {
        StringBuilder body = new StringBuilder();
        if (rows.isEmpty()) {
            body.append("<p><em>No spaces declared yet.</em></p>");
        } else {
            body.append("<ul>");
            for (Row row : rows) {
                String refLocal = stripPrefix(row.spaceRef(), SpacesVocab.NPAS_NAMESPACE);
                body.append("<li><code>").append(escape(row.spaceIri())).append("</code>")
                        .append(" — ref <code>").append(escape(refLocal)).append("</code>")
                        .append(", root <a href=\"").append(escape(row.rootNanopub())).append("\"><code>")
                        .append(escape(row.rootNanopub())).append("</code></a></li>");
            }
            body.append("</ul>");
        }
        return "<!DOCTYPE html>\n"
                + "<html lang='en'>\n"
                + "<head>\n"
                + "<title>Nanopub Query: Spaces</title>\n"
                + "<meta charset='utf-8'>\n"
                + "<link rel=\"stylesheet\" href=\"/style.css\">\n"
                + "</head>\n"
                + "<body>\n"
                + "<h3>Spaces</h3>"
                + "<p><a href=\"/spaces.json\">JSON</a></p>"
                + "<p>Declared spaces (from <code>npa:spacesGraph</code>):</p>"
                + body
                + "</body>\n"
                + "</html>";
    }

    static String renderJson(List<Row> rows) {
        StringBuilder out = new StringBuilder();
        out.append("{\"spaces\":[");
        boolean first = true;
        for (Row row : rows) {
            if (!first) out.append(',');
            first = false;
            out.append("{\"spaceIri\":\"").append(jsonEscape(row.spaceIri())).append('"')
                    .append(",\"spaceRef\":\"").append(jsonEscape(row.spaceRef())).append('"')
                    .append(",\"rootNanopub\":\"").append(jsonEscape(row.rootNanopub())).append("\"}");
        }
        out.append("]}");
        return out.toString();
    }

    private static String stripPrefix(String s, String prefix) {
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String jsonEscape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}

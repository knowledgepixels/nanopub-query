package com.knowledgepixels.query;

/**
 * Backs {@code GET /tools/<repo>/sparql-editor.html}: SIB's
 * <a href="https://github.com/sib-swiss/sparql-editor">sparql-editor</a> web
 * component pointed at one repository's SPARQL endpoint (issue #51).
 *
 * <p>This sits <em>next to</em> the plain YASGUI page at
 * {@code /tools/<repo>/yasgui.html} rather than replacing it — both are linked
 * from {@code /page/<repo>}. The two are not interchangeable: YASGUI opens with
 * the canned {@link Utils#defaultQuery} and nothing else, while sparql-editor
 * adds VoID-driven autocomplete for classes and properties, endpoint-provided
 * example queries, and a class overview. Which one is more useful depends on
 * what the repository actually carries, so the choice is left to the user.
 *
 * <p>Rendering is static and side-effect-free so it can be unit-tested without
 * a running server, following {@link SpacesListingRoute}.
 */
final class SparqlEditorRoute {

    private SparqlEditorRoute() {}

    /**
     * Matches the editor URL and captures the repository name. Group {@code $1$2}
     * is the repo, which may be two segments (e.g. {@code type/<hash>}) — the same
     * shape the YASGUI route accepts, and what {@code /repo/<repo>} expects.
     */
    static final String PATH_PATTERN = "^/tools/([a-zA-Z0-9-_]+)(/([a-zA-Z0-9-_]+))?/sparql-editor\\.html$";

    /**
     * Pinned version of the web component, loaded as a self-contained ES module
     * from jsDelivr (the CDN the YASGUI page already uses). The published bundle
     * has no bare imports, so no import map or module shim is needed — but it is
     * ~5 MB, which is why this is a separate opt-in page and not something added
     * to every repo page.
     */
    static final String COMPONENT_VERSION = "0.2.15";

    private static final String COMPONENT_URL =
            "https://cdn.jsdelivr.net/npm/@sib-swiss/sparql-editor@" + COMPONENT_VERSION + "/dist/sparql-editor.js";

    /**
     * Extracts the repository name from a path already known to match
     * {@link #PATH_PATTERN}.
     *
     * @param normalizedPath the request's normalized path
     * @return the repository name, one or two segments
     */
    static String repoFromPath(String normalizedPath) {
        return normalizedPath.replaceFirst(PATH_PATTERN, "$1$2");
    }

    /**
     * Renders the editor page for one repository.
     *
     * <p>The endpoint is resolved client-side against {@code window.location}
     * rather than baked in server-side: this instance sits behind nginx and has
     * no reliable view of the external scheme and host. It is passed as a data
     * attribute instead of interpolated into JavaScript, so no repo name can
     * escape into script context regardless of how the caller obtained it.
     *
     * @param repo the repository name
     * @return a complete HTML page
     */
    static String renderHtml(String repo) {
        String safeRepo = escape(repo);
        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>Nanopub Query SPARQL Editor for repository: " + safeRepo + "</title>\n"
                + "<link rel=\"stylesheet\" href=\"/style.css\">\n"
                // The shared stylesheet is sized for plain listing pages; both rules below
                // would otherwise leak into the editor's own widgets and result tables.
                + "<style>\n"
                + "sparql-editor { display: block; font-size: 1rem; }\n"
                + "sparql-editor td { white-space: normal; }\n"
                + "</style>\n"
                + "<script type=\"module\" src=\"" + COMPONENT_URL + "\"></script>\n"
                + "</head>\n"
                + "<body>\n"
                + "<h3>Nanopub Query SPARQL Editor for repository: " + safeRepo + "</h3>\n"
                + "<p>Endpoint: <a href=\"/repo/" + safeRepo + "\">/repo/" + safeRepo + "</a>"
                + " &middot; <a href=\"/tools/" + safeRepo + "/yasgui.html\">plain YASGUI editor</a>"
                + " &middot; <a href=\"/page/" + safeRepo + "\">repo page</a></p>\n"
                + "<noscript><p><strong>This editor needs JavaScript.</strong>"
                + " Query the endpoint directly at <code>/repo/" + safeRepo + "</code> instead.</p></noscript>\n"
                + "<sparql-editor id=\"sparql-editor\" data-endpoint-path=\"/repo/" + safeRepo + "\""
                // POST because nanopub queries routinely outgrow what a URL can carry; the
                // proxy allows GET and POST on /repo/* and rejects updates on both.
                + " default-method=\"POST\"></sparql-editor>\n"
                + "<script>\n"
                // Runs during parse, i.e. before the deferred module upgrades the element,
                // so connectedCallback already sees the endpoint attribute.
                + "(function () {\n"
                + "  var el = document.getElementById('sparql-editor');\n"
                + "  el.setAttribute('endpoint', new URL(el.dataset.endpointPath, window.location.href).href);\n"
                + "})();\n"
                + "</script>\n"
                + "</body>\n"
                + "</html>";
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
}

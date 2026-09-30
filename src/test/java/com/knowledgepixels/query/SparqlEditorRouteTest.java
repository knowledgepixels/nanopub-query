package com.knowledgepixels.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #51: SIB's sparql-editor is offered <em>next to</em> the plain YASGUI
 * page, not instead of it. These tests pin the two things that page has to get
 * right and that no compiler checks: the URL shape it is reachable under
 * (including the two-segment repo names {@code /repo/<repo>} accepts), and that
 * the repo name never reaches script context unescaped.
 */
class SparqlEditorRouteTest {

    private static boolean matches(String path) {
        return path.matches(SparqlEditorRoute.PATH_PATTERN);
    }

    @Test
    void pathPatternAcceptsSingleAndTwoSegmentRepos() {
        assertTrue(matches("/tools/full/sparql-editor.html"));
        assertTrue(matches("/tools/last30d/sparql-editor.html"));
        assertTrue(matches("/tools/type/3ef5b115/sparql-editor.html"));
        assertTrue(matches("/tools/pubkey/8435276e/sparql-editor.html"));
    }

    @Test
    void pathPatternRejectsEverythingElse() {
        assertFalse(matches("/tools/full/yasgui.html"), "must not swallow the YASGUI route");
        assertFalse(matches("/tools/sparql-editor.html"), "repo name is required");
        assertFalse(matches("/tools/a/b/c/sparql-editor.html"), "at most two repo segments");
        assertFalse(matches("/tools/full/sparql-editor.html/extra"));
        assertFalse(matches("/tools/../sparql-editor.html"));
    }

    @Test
    void repoIsExtractedIncludingItsSecondSegment() {
        assertEquals("full", SparqlEditorRoute.repoFromPath("/tools/full/sparql-editor.html"));
        assertEquals("type/3ef5b115", SparqlEditorRoute.repoFromPath("/tools/type/3ef5b115/sparql-editor.html"));
    }

    @Test
    void pageWiresTheComponentToThisRepoEndpoint() {
        String html = SparqlEditorRoute.renderHtml("type/3ef5b115");
        assertTrue(html.contains("<sparql-editor"), "the web component must be on the page");
        assertTrue(html.contains("data-endpoint-path=\"/repo/type/3ef5b115\""),
                "the component must point at this repo's endpoint");
        assertTrue(html.contains("@sib-swiss/sparql-editor@" + SparqlEditorRoute.COMPONENT_VERSION),
                "the CDN import must stay version-pinned");
        assertTrue(html.contains("type=\"module\""), "the bundle is an ES module");
    }

    /**
     * The endpoint is resolved client-side because the instance sits behind nginx
     * and cannot know its external scheme and host. That resolution must read the
     * repo from a data attribute — interpolating it into the inline script would
     * put a path segment into script context.
     */
    @Test
    void endpointIsResolvedAgainstTheBrowserLocation() {
        String html = SparqlEditorRoute.renderHtml("full");
        assertTrue(html.contains("new URL(el.dataset.endpointPath, window.location.href)"));
        assertFalse(html.contains("endpoint=\"/repo/"), "no server-baked absolute endpoint");
    }

    @Test
    void pageLinksBackToYasguiRatherThanReplacingIt() {
        String html = SparqlEditorRoute.renderHtml("full");
        assertTrue(html.contains("/tools/full/yasgui.html"));
        assertTrue(html.contains("/page/full"));
    }

    @Test
    void repoNameIsHtmlEscaped() {
        // The route regex cannot produce this, but renderHtml must not depend on
        // its only caller for that.
        String html = SparqlEditorRoute.renderHtml("\"><script>alert(1)</script>");
        assertFalse(html.contains("<script>alert(1)"), "raw script must not survive the renderer");
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
    }
}

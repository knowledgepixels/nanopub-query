package com.knowledgepixels.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpacesListingRouteTest {

    private static final SpacesListingRoute.Row ROW_A = new SpacesListingRoute.Row(
            "https://example.org/space/alpha",
            "http://purl.org/nanopub/admin/space/RA_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa_aaaaaaaa",
            "https://w3id.org/np/RA_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

    private static final SpacesListingRoute.Row ROW_B = new SpacesListingRoute.Row(
            "https://example.org/space/beta",
            "http://purl.org/nanopub/admin/space/RA_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb_bbbbbbbb",
            "https://w3id.org/np/RA_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

    @Test
    void html_emptyRows_rendersPlaceholder() {
        String html = SpacesListingRoute.renderHtml(List.of());
        assertTrue(html.contains("No spaces declared yet"),
                "empty listing should show explicit placeholder");
        assertTrue(html.contains("<title>Nanopub Query: Spaces</title>"));
    }

    @Test
    void html_rendersOneListItemPerRow() {
        String html = SpacesListingRoute.renderHtml(List.of(ROW_A, ROW_B));
        assertEquals(2, countOccurrences(html, "<li>"),
                "expected one <li> per row");
        assertTrue(html.contains("https://example.org/space/alpha"));
        assertTrue(html.contains("https://example.org/space/beta"));
        // The space-ref prefix should be stripped to its local name in the UI.
        assertTrue(html.contains("RA_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa_aaaaaaaa"));
        assertTrue(!html.contains("http://purl.org/nanopub/admin/space/"),
                "spaceref namespace should be stripped from the displayed local name");
    }

    @Test
    void html_escapesHtmlSpecialCharacters() {
        var hostile = new SpacesListingRoute.Row(
                "https://example.org/<script>alert(1)</script>",
                "http://purl.org/nanopub/admin/space/local",
                "https://w3id.org/np/RA_safe");
        String html = SpacesListingRoute.renderHtml(List.of(hostile));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"),
                "HTML special characters in spaceIri must be escaped");
        assertTrue(!html.contains("<script>alert"),
                "raw <script> must not survive the renderer");
    }

    @Test
    void json_emptyRows_emptyArray() {
        assertEquals("{\"spaces\":[]}", SpacesListingRoute.renderJson(List.of()));
    }

    @Test
    void json_rendersAllFields() {
        String json = SpacesListingRoute.renderJson(List.of(ROW_A));
        assertTrue(json.startsWith("{\"spaces\":["));
        assertTrue(json.endsWith("]}"));
        assertTrue(json.contains("\"spaceIri\":\"https://example.org/space/alpha\""));
        assertTrue(json.contains("\"spaceRef\":\"http://purl.org/nanopub/admin/space/RA_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa_aaaaaaaa\""));
        assertTrue(json.contains("\"rootNanopub\":\"https://w3id.org/np/RA_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\""));
    }

    @Test
    void json_escapesQuotesAndBackslashes() {
        var hostile = new SpacesListingRoute.Row(
                "iri-with-\"quote\"-and-\\back",
                "ref",
                "root");
        String json = SpacesListingRoute.renderJson(List.of(hostile));
        assertTrue(json.contains("iri-with-\\\"quote\\\"-and-\\\\back"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}

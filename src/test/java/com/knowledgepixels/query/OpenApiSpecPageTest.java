package com.knowledgepixels.query;

import com.knowledgepixels.query.GrlcSpec.InvalidGrlcSpecException;
import io.vertx.core.MultiMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.server.GetNanopub;
import org.nanopub.testsuite.NanopubTestSuite;
import org.nanopub.testsuite.TestSuiteEntry;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

class OpenApiSpecPageTest {

    private final String baseUri = "/openapi/spec/";
    private final String artifactCode = "RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA";
    private final TestSuiteEntry testSuiteEntry = NanopubTestSuite.getLatest().getByArtifactCode(artifactCode).getFirst();
    private Nanopub mockNanopub;
    private final String queryPart = "get-participation.rq";

    @BeforeEach
    void setUp() throws MalformedNanopubException, IOException {
        mockNanopub = new NanopubImpl(testSuiteEntry.toFile());
    }

    @Test
    void constructWithNullApiVersion() throws InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            MultiMap parameters = MultiMap.caseInsensitiveMultiMap();
            OpenApiSpecPage page = new OpenApiSpecPage(baseUri + artifactCode + "/" + queryPart, parameters);
            assertNotNull(page);
        }
    }

    @Test
    void constructWithSpecificApiVersion() throws InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            MultiMap parameters = MultiMap.caseInsensitiveMultiMap();
            parameters.add("api-version", "random-version");
            OpenApiSpecPage page = new OpenApiSpecPage(baseUri + artifactCode + "/" + queryPart, parameters);
            assertNotNull(page);
        }
    }

    @Test
    void constructWithInvalidUrl() {
        try {
            new OpenApiSpecPage("https://invalid-url", MultiMap.caseInsensitiveMultiMap());
            fail();
        } catch (InvalidGrlcSpecException ex) {
            // all good
        }
    }

    @Test
    void constructWithParameters() throws InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            MultiMap parameters = MultiMap.caseInsensitiveMultiMap();
            parameters.add("api-version", "latest");
            OpenApiSpecPage page = new OpenApiSpecPage(baseUri + artifactCode + "/" + queryPart, parameters);
            assertNotNull(page);
        }
    }

    @Test
    void getSpec() throws InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            MultiMap parameters = MultiMap.caseInsensitiveMultiMap();
            OpenApiSpecPage page = new OpenApiSpecPage(baseUri + artifactCode + "/" + queryPart, parameters);
            String expectedSpec = """
                    openapi: 3.0.4
                    info:
                      title: Get participation links
                      description: 'API definition source: <a target="_blank" href="https://w3id.org/np/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA"><svg
                        height="0.8em" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 8 8"><path d="M5,8H8L3,0H0M8,4.8V0H5M0,3.2V8H3"/></svg>
                        RA6T-YLqLn</a>'
                      version: RA6T-YLqLn
                    servers:
                    - url: http://localhost:9393/api/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA
                      description: This Nanopub Query instance
                    paths:
                      /get-participation:
                        get:
                          description: This query returns all participation links.
                          parameters: [
                            ]
                          responses:
                            '200':
                              content:
                                text/csv: {
                                  }
                                application/json: {
                                  }
                                application/xml: {
                                  }
                              description: result table
                    """;

            assertEquals(expectedSpec, page.getSpec());
        }
    }

    /**
     * Regression test for issue #50: a query with two or more parameters that share the same
     * schema must not be serialized with YAML anchors/aliases ({@code &id001} / {@code *id001}).
     * Reusing a single schema map instance across parameters made SnakeYAML emit aliases, which
     * some OpenAPI validators fail to resolve, producing "instance failed to match exactly one
     * schema (matched 0 out of 2)".
     */
    @Test
    void multiParamSpecHasNoYamlAnchors() throws InvalidGrlcSpecException, MalformedNanopubException, IOException, URISyntaxException {
        File ferSearch = new File(getClass().getResource("/openapi/fer_search.trig").toURI());
        Nanopub multiParamNanopub = new NanopubImpl(ferSearch);
        String ferArtifactCode = "RALYGDDqaxlCSDqvtX2QCNnO59f7_haWraFz0rFfZdYtc";
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(multiParamNanopub);
            OpenApiSpecPage page = new OpenApiSpecPage(baseUri + ferArtifactCode + "/fer_search",
                    MultiMap.caseInsensitiveMultiMap());
            String spec = page.getSpec();

            // Both parameters must be rendered, each with its own inline schema.
            assertEquals(2, spec.split("(?m)^\\s*- in: query$").length - 1,
                    "expected two query parameters in the spec");
            assertFalse(spec.contains("&id"), "spec must not contain YAML anchors: " + spec);
            assertFalse(spec.contains("*id"), "spec must not contain YAML aliases: " + spec);
            assertEquals(2, countOccurrences(spec, "type: string"),
                    "each parameter's schema must be inlined, not aliased");
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

}
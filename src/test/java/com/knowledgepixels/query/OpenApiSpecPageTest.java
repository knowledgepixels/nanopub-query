package com.knowledgepixels.query;

import com.knowledgepixels.query.GrlcSpec.InvalidGrlcSpecException;
import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.server.GetNanopub;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

class OpenApiSpecPageTest {

    private final String baseUri = "/openapi/spec/";
    private final String artifactCode = "RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA";
    private final String queryPart = "get-participation.rq";

    @Test
    void constructWithNullApiVersion() throws MalformedNanopubException, IOException, InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(Objects.requireNonNull(this.getClass().getResource("/testsuite/valid/signed/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA.trig")).getPath()));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            MultiMap parameters = MultiMap.caseInsensitiveMultiMap();
            OpenApiSpecPage page = new OpenApiSpecPage(baseUri + artifactCode + "/" + queryPart, parameters);
            assertNotNull(page);
        }
    }

    @Test
    void constructWithSpecificApiVersion() throws MalformedNanopubException, IOException, InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(Objects.requireNonNull(this.getClass().getResource("/testsuite/valid/signed/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA.trig")).getPath()));
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
    void constructWithParameters() throws MalformedNanopubException, IOException, InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(Objects.requireNonNull(this.getClass().getResource("/testsuite/valid/signed/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA.trig")).getPath()));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            MultiMap parameters = MultiMap.caseInsensitiveMultiMap();
            parameters.add("api-version", "latest");
            OpenApiSpecPage page = new OpenApiSpecPage(baseUri + artifactCode + "/" + queryPart, parameters);
            assertNotNull(page);
        }
    }

    @Test
    void getSpec() throws MalformedNanopubException, IOException, InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(Objects.requireNonNull(this.getClass().getResource("/testsuite/valid/signed/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA.trig")).getPath()));
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
                              description: result table
                    """;

            assertEquals(expectedSpec, page.getSpec());
        }
    }

}
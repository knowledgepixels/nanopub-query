package com.knowledgepixels.query;

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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mockStatic;

class GrlcSpecPageTest {

    private final String baseUri = "https://w3id.org/np/";
    private final String artifactCode = "RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA";
    private final String queryName = "get-participation";

    @Test
    void constructWithInvalidUrl() {
        GrlcSpecPage page = new GrlcSpecPage("https://invalid-url", MultiMap.caseInsensitiveMultiMap());
        assertNull(page.getSpec());
    }

    @Test
    void constructWithParameters() throws MalformedNanopubException, IOException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(Objects.requireNonNull(this.getClass().getResource("/testsuite/valid/signed/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA.trig")).getPath()));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            MultiMap parameters = MultiMap.caseInsensitiveMultiMap();
            parameters.add("api-version", "latest");
            GrlcSpecPage page = new GrlcSpecPage(baseUri + artifactCode + "/" + queryName, parameters);
            assertNotNull(page);
        }
    }

    @Test
    void getSpecWithEmptyQueryPart() throws MalformedNanopubException, IOException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(Objects.requireNonNull(this.getClass().getResource("/testsuite/valid/signed/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA.trig")).getPath()));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            GrlcSpecPage page = new GrlcSpecPage(baseUri + artifactCode + "/", MultiMap.caseInsensitiveMultiMap());
            String expectedSpec = """
                    title: "Get participation links"
                    description: "This query returns all participation links."
                    contact:
                      name: "https://orcid.org/0000-0002-1267-0234"
                      url: https://orcid.org/0000-0002-1267-0234
                    licence: http://www.apache.org/licenses/LICENSE-2.0
                    queries:
                      - http://query:9393/https://w3id.org/np/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA/get-participation.rq""";
            assertEquals(expectedSpec, page.getSpec());
        }
    }

    @Test
    void getSpecWithRqExtension() throws MalformedNanopubException, IOException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(Objects.requireNonNull(this.getClass().getResource("/testsuite/valid/signed/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA.trig")).getPath()));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            GrlcSpecPage page = new GrlcSpecPage(baseUri + artifactCode + "/" + queryName + ".rq", MultiMap.caseInsensitiveMultiMap());
            String expectedSpec = """
                    #+ summary: "Get participation links"
                    #+ description: "This query returns all participation links."
                    #+ licence: http://www.apache.org/licenses/LICENSE-2.0
                    #+ endpoint: http://query:9393/repo/full
                    
                    prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>\r
                    prefix dct: <http://purl.org/dc/terms/>\r
                    prefix np: <http://www.nanopub.org/nschema#>\r
                    prefix npa: <http://purl.org/nanopub/admin/>\r
                    prefix npx: <http://purl.org/nanopub/x/>\r
                    prefix wd: <http://www.wikidata.org/entity/>\r
                    \r
                    select ?person ?event ?np ?date where {\r
                      graph npa:graph {\r
                        ?np npa:hasValidSignatureForPublicKey ?pubkey .\r
                        filter not exists { ?npx npx:invalidates ?np ; npa:hasValidSignatureForPublicKey ?pubkey . }\r
                        ?np dct:created ?date .\r
                        ?np np:hasAssertion ?a .\r
                        optional { ?np rdfs:label ?label }\r
                      }\r
                      graph ?a {\r
                        ?person wd:P1344 ?event .\r
                      }\r
                    } order by desc(?date)""";
            assertEquals(expectedSpec, page.getSpec());
        }
    }

    @Test
    void getSpecWithInvalidQueryPart() throws MalformedNanopubException, IOException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(Objects.requireNonNull(this.getClass().getResource("/testsuite/valid/signed/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA.trig")).getPath()));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            GrlcSpecPage page = new GrlcSpecPage(baseUri + artifactCode + "/invalid-query.rq", MultiMap.caseInsensitiveMultiMap());
            assertNull(page.getSpec());
        }
    }

}
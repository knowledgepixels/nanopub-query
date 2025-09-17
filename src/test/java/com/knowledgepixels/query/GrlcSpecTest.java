package com.knowledgepixels.query;

import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.server.GetNanopub;

import com.knowledgepixels.query.GrlcSpec.InvalidGrlcSpecException;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mockStatic;

class GrlcSpecTest {

    private final String baseUri = "/grlc-spec/";
    private final String artifactCode = "RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA";
    private final String queryPart = "get-participation.rq";

    @Test
    void constructWithNullApiVersion() throws MalformedNanopubException, IOException, InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(Objects.requireNonNull(this.getClass().getResource("/testsuite/valid/signed/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA.trig")).getPath()));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            MultiMap parameters = MultiMap.caseInsensitiveMultiMap();
            GrlcSpec page = new GrlcSpec(baseUri + artifactCode + "/" + queryPart, parameters);
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
            GrlcSpec page = new GrlcSpec(baseUri + artifactCode + "/" + queryPart, parameters);
            assertNotNull(page);
        }
    }

    @Test
    void constructWithInvalidUrl() throws InvalidGrlcSpecException {
        try {
            new GrlcSpec("https://invalid-url", MultiMap.caseInsensitiveMultiMap());
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
            GrlcSpec page = new GrlcSpec(baseUri + artifactCode + "/" + queryPart, parameters);
            assertNotNull(page);
        }
    }

    @Test
    void getSpecWithEmptyQueryPart() throws MalformedNanopubException, IOException, InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(Objects.requireNonNull(this.getClass().getResource("/testsuite/valid/signed/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA.trig")).getPath()));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            GrlcSpec page = new GrlcSpec(baseUri + artifactCode + "/", MultiMap.caseInsensitiveMultiMap());
            String expectedSpec = """
                    title: "Get participation links"
                    description: "This query returns all participation links."
                    contact:
                      name: "https://orcid.org/0000-0002-1267-0234"
                      url: https://orcid.org/0000-0002-1267-0234
                    licence: http://www.apache.org/licenses/LICENSE-2.0
                    queries:
                      - http://query:9393/grlc-spec/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA/get-participation.rq""";
            System.err.println(page.getSpec());
            System.err.println(expectedSpec);
            assertEquals(expectedSpec, page.getSpec());
        }
    }

    @Test
    void getSpec() throws MalformedNanopubException, IOException, InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(Objects.requireNonNull(this.getClass().getResource("/testsuite/valid/signed/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA.trig")).getPath()));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            GrlcSpec page = new GrlcSpec(baseUri + artifactCode + "/" + queryPart, MultiMap.caseInsensitiveMultiMap());
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
    void getSpecWithInvalidQueryPart() throws MalformedNanopubException, IOException, InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(Objects.requireNonNull(this.getClass().getResource("/testsuite/valid/signed/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA.trig")).getPath()));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            try {
                new GrlcSpec(baseUri + artifactCode + "/invalid-query.rq", MultiMap.caseInsensitiveMultiMap());
                fail();
            } catch (InvalidGrlcSpecException ex) {
                // all good
            }
        }
    }

}
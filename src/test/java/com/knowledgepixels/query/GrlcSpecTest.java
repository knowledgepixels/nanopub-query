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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mockStatic;

class GrlcSpecTest {

    private final String BASE_URI = "/grlc-spec/";
    private final String ARTIFACT_CODE = "RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA";
    private final String QUERY_PART = "get-participation.rq";
    private final String EXAMPLE_NANOPUBLICATION_PATH = Objects.requireNonNull(this.getClass().getResource("/testsuite/valid/signed/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA.trig")).getPath();
    private final String REPO_NAME = "full";
    private final String QUERY_NAME = "get-participation";
    private final String DESCRIPTION = "This query returns all participation links.";
    private final String LABEL = "Get participation links";

    @Test
    void constructWithNullApiVersion() throws MalformedNanopubException, IOException, InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(EXAMPLE_NANOPUBLICATION_PATH));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            MultiMap parameters = MultiMap.caseInsensitiveMultiMap();
            GrlcSpec page = new GrlcSpec(BASE_URI + ARTIFACT_CODE + "/" + QUERY_PART, parameters);
            assertNotNull(page);
        }
    }

    @Test
    void constructWithSpecificApiVersion() throws MalformedNanopubException, IOException, InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(EXAMPLE_NANOPUBLICATION_PATH));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            MultiMap parameters = MultiMap.caseInsensitiveMultiMap();
            parameters.add("api-version", "random-version");
            GrlcSpec page = new GrlcSpec(BASE_URI + ARTIFACT_CODE + "/" + QUERY_PART, parameters);
            assertNotNull(page);
        }
    }

    @Test
    void constructWithInvalidUrl() {
        assertThrows(InvalidGrlcSpecException.class, () -> new GrlcSpec("https://invalid-url", MultiMap.caseInsensitiveMultiMap()));
    }

    @Test
    void constructWithParameters() throws MalformedNanopubException, IOException, InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(EXAMPLE_NANOPUBLICATION_PATH));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            MultiMap parameters = MultiMap.caseInsensitiveMultiMap();
            parameters.add("api-version", "latest");
            GrlcSpec page = new GrlcSpec(BASE_URI + ARTIFACT_CODE + "/" + QUERY_PART, parameters);
            assertNotNull(page);
        }
    }

    @Test
    void getSpecWithEmptyQueryPart() throws MalformedNanopubException, IOException, InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(EXAMPLE_NANOPUBLICATION_PATH));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            GrlcSpec page = new GrlcSpec(BASE_URI + ARTIFACT_CODE + "/", MultiMap.caseInsensitiveMultiMap());
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
            Nanopub mockNanopub = new NanopubImpl(new File(EXAMPLE_NANOPUBLICATION_PATH));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            GrlcSpec page = new GrlcSpec(BASE_URI + ARTIFACT_CODE + "/" + QUERY_PART, MultiMap.caseInsensitiveMultiMap());
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
            Nanopub mockNanopub = new NanopubImpl(new File(EXAMPLE_NANOPUBLICATION_PATH));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            assertThrows(InvalidGrlcSpecException.class, () -> new GrlcSpec(BASE_URI + ARTIFACT_CODE + "/invalid-query.rq", MultiMap.caseInsensitiveMultiMap()));
        }
    }

    @Test
    void isIriPlaceholder() {
        assertTrue(GrlcSpec.isIriPlaceholder("example_iri"));
    }

    @Test
    void isIriPlaceholderWhenNotEndingWithIri() {
        assertFalse(GrlcSpec.isIriPlaceholder("example"));
    }

    @Test
    void isIriPlaceholderWhenEmpty() {
        assertFalse(GrlcSpec.isIriPlaceholder(""));
    }

    @Test
    void isIriPlaceholderWhenNull() {
        assertThrows(NullPointerException.class, () -> GrlcSpec.isIriPlaceholder(null));
    }

    @Test
    void isOptionalPlaceholder() {
        assertTrue(GrlcSpec.isOptionalPlaceholder("__example"));
    }

    @Test
    void isOptionalPlaceholderNotValid() {
        assertFalse(GrlcSpec.isOptionalPlaceholder("placeholder"));
    }

    @Test
    void isOptionalPlaceholderWhenEmpty() {
        assertFalse(GrlcSpec.isOptionalPlaceholder(""));
    }

    @Test
    void isOptionalPlaceholderWhenNull() {
        assertThrows(NullPointerException.class, () -> GrlcSpec.isOptionalPlaceholder(null));
    }

    @Test
    void isMultiPlaceholderValid() {
        assertTrue(GrlcSpec.isMultiPlaceholder("example_multi"));
        assertTrue(GrlcSpec.isMultiPlaceholder("example_multi_iri"));
    }

    @Test
    void isMultiPlaceholderValidNotValid() {
        assertFalse(GrlcSpec.isMultiPlaceholder("placeholder_iri"));
        assertFalse(GrlcSpec.isMultiPlaceholder("placeholder"));
    }

    @Test
    void isMultiPlaceholderValidWhenEmpty() {
        assertFalse(GrlcSpec.isMultiPlaceholder(""));
    }

    @Test
    void isMultiPlaceholderWhenNull() {
        assertThrows(NullPointerException.class, () -> GrlcSpec.isMultiPlaceholder(null));
    }

    @Test
    void serializeIri() {
        String iri = "https://example.org/resource";
        assertEquals("<https://example.org/resource>", GrlcSpec.serializeIri(iri));
    }

    @Test
    void serializeLiteral() {
        String literal = "Example Literal";
        assertEquals("\"Example Literal\"", GrlcSpec.serializeLiteral(literal));
    }

    @Test
    void getParameters() throws MalformedNanopubException, IOException, InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(EXAMPLE_NANOPUBLICATION_PATH));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            MultiMap parameters = MultiMap.caseInsensitiveMultiMap();
            parameters.add("api-version", "latest");
            GrlcSpec page = new GrlcSpec(BASE_URI + ARTIFACT_CODE + "/" + QUERY_PART, parameters);
            assertEquals(parameters, page.getParameters());
        }
    }

    @Test
    void getNanopub() throws MalformedNanopubException, IOException, InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(EXAMPLE_NANOPUBLICATION_PATH));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            MultiMap parameters = MultiMap.caseInsensitiveMultiMap();
            GrlcSpec grlcSpec = new GrlcSpec(BASE_URI + ARTIFACT_CODE + "/" + QUERY_PART, parameters);
            assertEquals(mockNanopub, grlcSpec.getNanopub());
        }
    }

    @Test
    void getQueryName() throws InvalidGrlcSpecException, MalformedNanopubException, IOException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(EXAMPLE_NANOPUBLICATION_PATH));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            GrlcSpec grlcSpec = new GrlcSpec(BASE_URI + ARTIFACT_CODE + "/", MultiMap.caseInsensitiveMultiMap());
            assertEquals(QUERY_NAME, grlcSpec.getQueryName());
        }
    }

    @Test
    void getDescription() throws InvalidGrlcSpecException, MalformedNanopubException, IOException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(EXAMPLE_NANOPUBLICATION_PATH));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            GrlcSpec grlcSpec = new GrlcSpec(BASE_URI + ARTIFACT_CODE + "/", MultiMap.caseInsensitiveMultiMap());
            assertEquals(DESCRIPTION, grlcSpec.getDescription());
        }
    }

    @Test
    void getArtifactCode() throws InvalidGrlcSpecException, MalformedNanopubException, IOException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(EXAMPLE_NANOPUBLICATION_PATH));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            GrlcSpec grlcSpec = new GrlcSpec(BASE_URI + ARTIFACT_CODE + "/", MultiMap.caseInsensitiveMultiMap());
            assertEquals(ARTIFACT_CODE, grlcSpec.getArtifactCode());
        }
    }

    @Test
    void getLabel() throws InvalidGrlcSpecException, MalformedNanopubException, IOException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(EXAMPLE_NANOPUBLICATION_PATH));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            GrlcSpec grlcSpec = new GrlcSpec(BASE_URI + ARTIFACT_CODE + "/", MultiMap.caseInsensitiveMultiMap());
            assertEquals(LABEL, grlcSpec.getLabel());
        }
    }

    @Test
    void getQueryContent() throws InvalidGrlcSpecException, MalformedNanopubException, IOException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(EXAMPLE_NANOPUBLICATION_PATH));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            GrlcSpec grlcSpec = new GrlcSpec(BASE_URI + ARTIFACT_CODE + "/", MultiMap.caseInsensitiveMultiMap());
            assertEquals("""
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
                    } order by desc(?date)""", grlcSpec.getQueryContent());
        }
    }

    @Test
    void getRepoName() throws MalformedNanopubException, IOException, InvalidGrlcSpecException {
        try (MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {
            Nanopub mockNanopub = new NanopubImpl(new File(EXAMPLE_NANOPUBLICATION_PATH));
            mockedGetNanopub.when(() -> GetNanopub.get(any())).thenReturn(mockNanopub);
            GrlcSpec grlcSpec = new GrlcSpec(BASE_URI + ARTIFACT_CODE + "/", MultiMap.caseInsensitiveMultiMap());
            assertEquals(REPO_NAME, grlcSpec.getRepoName());
        }
    }

    @Test
    void getParamNameFromMultiPlaceholder() {
        String placeholder = "example_multi";
        assertEquals("example", GrlcSpec.getParamName(placeholder));
    }

    @Test
    void getParamNameFromMultiIriPlaceholder() {
        String placeholder = "example_multi_iri";
        assertEquals("example", GrlcSpec.getParamName(placeholder));
    }

    @Test
    void getParamNameFromIriPlaceholder() {
        String placeholder = "example_iri";
        assertEquals("example", GrlcSpec.getParamName(placeholder));
    }

    @Test
    void getParamNameFromOptionalPlaceholder() {
        assertEquals("example", GrlcSpec.getParamName("__example"));
        assertEquals("example", GrlcSpec.getParamName("__example_iri"));
        assertEquals("example", GrlcSpec.getParamName("__example_multi"));
        assertEquals("example", GrlcSpec.getParamName("__example_multi_iri"));
    }

}
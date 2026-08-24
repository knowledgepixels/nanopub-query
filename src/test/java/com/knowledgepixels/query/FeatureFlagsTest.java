package com.knowledgepixels.query;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class FeatureFlagsTest {

    private static void withEnv(String value, Runnable assertion) {
        withEnv("NANOPUB_QUERY_LOCAL_INSTANCE", value, assertion);
    }

    private static void withEnv(String name, String value, Runnable assertion) {
        try (MockedStatic<Utils> mockedUtils = mockStatic(Utils.class, CALLS_REAL_METHODS)) {
            mockedUtils.when(() -> Utils.getRawEnv(name)).thenReturn(value);
            assertion.run();
        }
    }

    @Test
    void localInstanceDefaultsToFalse() {
        withEnv(null, () -> assertFalse(FeatureFlags.localInstance()));
        withEnv("", () -> assertFalse(FeatureFlags.localInstance()));
    }

    @Test
    void localInstanceParsesTrueCaseInsensitively() {
        withEnv("true", () -> assertTrue(FeatureFlags.localInstance()));
        withEnv("TRUE", () -> assertTrue(FeatureFlags.localInstance()));
    }

    @Test
    void localInstanceStaysFalseForOtherValues() {
        withEnv("false", () -> assertFalse(FeatureFlags.localInstance()));
        withEnv("1", () -> assertFalse(FeatureFlags.localInstance()));
        withEnv("yes", () -> assertFalse(FeatureFlags.localInstance()));
    }

    private static void withTestRegistryEnv(String value, Runnable assertion) {
        withEnv("NANOPUB_QUERY_ALLOW_TEST_REGISTRY", value, assertion);
    }

    @Test
    void allowTestRegistryDefaultsToFalse() {
        withTestRegistryEnv(null, () -> assertFalse(FeatureFlags.allowTestRegistry()));
        withTestRegistryEnv("", () -> assertFalse(FeatureFlags.allowTestRegistry()));
    }

    @Test
    void allowTestRegistryParsesTrueCaseInsensitively() {
        withTestRegistryEnv("true", () -> assertTrue(FeatureFlags.allowTestRegistry()));
        withTestRegistryEnv("TRUE", () -> assertTrue(FeatureFlags.allowTestRegistry()));
    }

    @Test
    void allowTestRegistryStaysFalseForOtherValues() {
        withTestRegistryEnv("false", () -> assertFalse(FeatureFlags.allowTestRegistry()));
        withTestRegistryEnv("1", () -> assertFalse(FeatureFlags.allowTestRegistry()));
        withTestRegistryEnv("yes", () -> assertFalse(FeatureFlags.allowTestRegistry()));
    }

}

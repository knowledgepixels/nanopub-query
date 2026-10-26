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

    private static void withTestInstanceEnv(String value, Runnable assertion) {
        withEnv("NANOPUB_QUERY_TEST_INSTANCE", value, assertion);
    }

    @Test
    void testInstanceDefaultsToFalse() {
        withTestInstanceEnv(null, () -> assertFalse(FeatureFlags.testInstance()));
        withTestInstanceEnv("", () -> assertFalse(FeatureFlags.testInstance()));
    }

    @Test
    void testInstanceParsesTrueCaseInsensitively() {
        withTestInstanceEnv("true", () -> assertTrue(FeatureFlags.testInstance()));
        withTestInstanceEnv("TRUE", () -> assertTrue(FeatureFlags.testInstance()));
    }

    @Test
    void testInstanceStaysFalseForOtherValues() {
        withTestInstanceEnv("false", () -> assertFalse(FeatureFlags.testInstance()));
        withTestInstanceEnv("1", () -> assertFalse(FeatureFlags.testInstance()));
        withTestInstanceEnv("yes", () -> assertFalse(FeatureFlags.testInstance()));
    }

    @Test
    void testInstanceIsIndependentOfTheTestRegistryFlag() {
        // The two are deliberately separate: the motivating case for a self-declared
        // test instance is one paired with a *production* registry (issue #200), so
        // neither flag may quietly imply the other. Both variables are mocked here, so
        // the assertions hold whatever the environment running the tests happens to set.
        withEnvPair("NANOPUB_QUERY_TEST_INSTANCE", "true", "NANOPUB_QUERY_ALLOW_TEST_REGISTRY", null, () -> {
            assertTrue(FeatureFlags.testInstance());
            assertFalse(FeatureFlags.allowTestRegistry());
        });
        withEnvPair("NANOPUB_QUERY_TEST_INSTANCE", null, "NANOPUB_QUERY_ALLOW_TEST_REGISTRY", "true", () -> {
            assertFalse(FeatureFlags.testInstance());
            assertTrue(FeatureFlags.allowTestRegistry());
        });
    }

    private static void withEnvPair(String firstName, String firstValue, String secondName, String secondValue,
                                    Runnable assertion) {
        try (MockedStatic<Utils> mockedUtils = mockStatic(Utils.class, CALLS_REAL_METHODS)) {
            mockedUtils.when(() -> Utils.getRawEnv(firstName)).thenReturn(firstValue);
            mockedUtils.when(() -> Utils.getRawEnv(secondName)).thenReturn(secondValue);
            assertion.run();
        }
    }

}

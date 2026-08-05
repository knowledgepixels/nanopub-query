package com.knowledgepixels.query;

import com.knowledgepixels.query.JellyNanopubLoader.LoadingType;
import com.knowledgepixels.query.JellyNanopubLoader.RegistryMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class JellyNanopubLoaderTest {

    @Test
    void loadInitialWithAfterGreater() {
        try (MockedStatic<JellyNanopubLoader> mockedJellyLoader = mockStatic(JellyNanopubLoader.class, CALLS_REAL_METHODS)) {
            mockedJellyLoader.when(JellyNanopubLoader::fetchRegistryMetadata).thenReturn(new RegistryMetadata(5L, null, null, null, null, null, null));
            JellyNanopubLoader.loadInitial(10L);
            mockedJellyLoader.verify(() -> JellyNanopubLoader.loadBatch(anyLong(), any(LoadingType.class)), never());
        }
    }

    /*
    @Test
    void loadInitialWithException() {
        try (MockedStatic<JellyNanopubLoader> mockedJellyLoader = mockStatic(JellyNanopubLoader.class, CALLS_REAL_METHODS)) {
            mockedJellyLoader.when(JellyNanopubLoader::fetchRegistryMetadata).thenReturn(new RegistryMetadata(10L, null, null, null, null, null, null));
            // if loadBatch is mocked then the lastCommittedCounter is never increased therefore there is an infinite loop
            mockedJellyLoader.when(() -> JellyNanopubLoader.loadBatch(anyLong(), any(LoadingType.class))).thenThrow(new RuntimeException("This is just an example exception"));

            JellyNanopubLoader.loadInitial(5L);
        }
    }*/

    /**
     * Every metadata fetch resets the refresh clock, so the ~2 s update poll never
     * triggers an extra HEAD request of its own.
     */
    @Test
    void updateForwardingMetadataStampsTheRefreshClock() throws Exception {
        String savedCount = JellyNanopubLoader.lastNanopubCount;
        String savedTypes = JellyNanopubLoader.lastCoverageTypes;
        long savedMetadataAt = getLong("lastForwardingMetadataAtMs");
        try {
            setLong("lastForwardingMetadataAtMs", 0L);
            long before = System.currentTimeMillis();

            Method m = JellyNanopubLoader.class
                    .getDeclaredMethod("updateForwardingMetadata", RegistryMetadata.class);
            m.setAccessible(true);
            m.invoke(null, new RegistryMetadata(7L, null, "all", "viaSetting", "false", "4242", null));

            assertEquals("4242", JellyNanopubLoader.lastNanopubCount);
            assertEquals("all", JellyNanopubLoader.lastCoverageTypes);
            assertTrue(getLong("lastForwardingMetadataAtMs") >= before,
                    "the refresh clock must be stamped on every metadata update");
        } finally {
            JellyNanopubLoader.lastNanopubCount = savedCount;
            JellyNanopubLoader.lastCoverageTypes = savedTypes;
            setLong("lastForwardingMetadataAtMs", savedMetadataAt);
        }
    }

    /**
     * The mid-load refresh is rate-limited. This matters on the streaming hot path: it
     * is called from the per-50-nanopub progress block, so an unthrottled version would
     * issue a HEAD request several times a second for the whole of a resync.
     */
    @Test
    void midLoadRefreshIsSkippedWhileTheMetadataIsFresh() throws Exception {
        String savedCount = JellyNanopubLoader.lastNanopubCount;
        long savedMetadataAt = getLong("lastForwardingMetadataAtMs");
        try {
            long freshStamp = System.currentTimeMillis();
            setLong("lastForwardingMetadataAtMs", freshStamp);
            JellyNanopubLoader.lastNanopubCount = "sentinel";

            Method m = JellyNanopubLoader.class.getDeclaredMethod("maybeRefreshForwardingMetadata");
            m.setAccessible(true);
            m.invoke(null);

            // Exact-match, not merely "the count is unchanged": a refresh that actually
            // ran would re-stamp the clock even when the fetch itself failed.
            assertEquals(freshStamp, getLong("lastForwardingMetadataAtMs"),
                    "a fresh timestamp must short-circuit the refresh before any request");
            assertEquals("sentinel", JellyNanopubLoader.lastNanopubCount);
        } finally {
            JellyNanopubLoader.lastNanopubCount = savedCount;
            setLong("lastForwardingMetadataAtMs", savedMetadataAt);
        }
    }

    private static long getLong(String fieldName) throws Exception {
        Field f = JellyNanopubLoader.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.getLong(null);
    }

    private static void setLong(String fieldName, long value) throws Exception {
        Field f = JellyNanopubLoader.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.setLong(null, value);
    }

}
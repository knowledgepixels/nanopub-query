package com.knowledgepixels.query;

import com.knowledgepixels.query.JellyNanopubLoader.LoadingType;
import com.knowledgepixels.query.JellyNanopubLoader.RegistryMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

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

}
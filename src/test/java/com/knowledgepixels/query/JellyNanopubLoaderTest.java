package com.knowledgepixels.query;

import com.knowledgepixels.query.JellyNanopubLoader.LoadingType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class JellyNanopubLoaderTest {

    @Test
    void loadInitialWithAfterGreater() {
        try (MockedStatic<JellyNanopubLoader> mockedJellyLoader = mockStatic(JellyNanopubLoader.class, CALLS_REAL_METHODS)) {
            mockedJellyLoader.when(JellyNanopubLoader::fetchRegistryLoadCounter).thenReturn(5L);
            JellyNanopubLoader.loadInitial(10L);
            mockedJellyLoader.verify(() -> JellyNanopubLoader.loadBatch(anyLong(), any(LoadingType.class)), never());
        }
    }

    /*
    @Test
    void loadInitialWithException() {
        try (MockedStatic<JellyNanopubLoader> mockedJellyLoader = mockStatic(JellyNanopubLoader.class, CALLS_REAL_METHODS)) {
            mockedJellyLoader.when(JellyNanopubLoader::fetchRegistryLoadCounter).thenReturn(10L);
            // if loadBatch is mocked then the lastCommittedCounter is never increased therefore there is an infinite loop
            mockedJellyLoader.when(() -> JellyNanopubLoader.loadBatch(anyLong(), any(LoadingType.class))).thenThrow(new RuntimeException("This is just an example exception"));

            JellyNanopubLoader.loadInitial(5L);
        }
    }*/

}
package com.knowledgepixels.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrustStateRegistryTest {

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        // Reset the singleton so each test starts fresh.
        Field instance = TrustStateRegistry.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    void getCurrentHash_isEmptyBeforeAnythingIsSet() {
        assertFalse(TrustStateRegistry.get().getCurrentHash().isPresent());
    }

    @Test
    void setCurrentHash_isReflectedByGetCurrentHash() {
        TrustStateRegistry.get().setCurrentHash("abc123");
        assertTrue(TrustStateRegistry.get().getCurrentHash().isPresent());
        assertEquals("abc123", TrustStateRegistry.get().getCurrentHash().get());
    }

    @Test
    void setCurrentHash_overwritesPreviousValue() {
        TrustStateRegistry.get().setCurrentHash("first");
        TrustStateRegistry.get().setCurrentHash("second");
        assertEquals("second", TrustStateRegistry.get().getCurrentHash().get());
    }

    @Test
    void setCurrentHash_rejectsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> TrustStateRegistry.get().setCurrentHash(null));
    }

    @Test
    void setCurrentHash_rejectsEmpty() {
        assertThrows(IllegalArgumentException.class,
                () -> TrustStateRegistry.get().setCurrentHash(""));
    }

    @Test
    void getReturnsSameInstanceAcrossCalls() {
        TrustStateRegistry first = TrustStateRegistry.get();
        TrustStateRegistry second = TrustStateRegistry.get();
        assertSame(first, second);
    }

}

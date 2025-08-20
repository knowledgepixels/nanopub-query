package com.knowledgepixels.query;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class LocalNanopubLoaderTest {

    private final String loadDirectory = "load";

    @BeforeEach
    void setUp() {
        File directory = new File(loadDirectory);
        if (directory.exists()) {
            for (File file : Objects.requireNonNull(directory.listFiles())) {
                file.delete();
            }
            directory.delete();
        }
    }

    @AfterEach
    void tearDown() {
        File directory = new File(loadDirectory);
        if (directory.exists()) {
            for (File file : Objects.requireNonNull(directory.listFiles())) {
                file.delete();
            }
            directory.delete();
        }
    }

    @Test
    void initWhenLocalNanopubFileNotExist() {
        boolean result = LocalNanopubLoader.init();
        assertFalse(LocalNanopubLoader.loadNanopubsFile.exists());
        assertFalse(result);
    }

    @Test
    void initWhenLocalNanopubFileExists() throws IOException {
        File directory = new File(loadDirectory);
        if (!directory.exists()) {
            boolean created = directory.mkdirs();
            if (!created) {
                throw new IOException("Failed to create directory: " + directory.getAbsolutePath());
            }
        }
        File loadNanopubsFile = new File(LocalNanopubLoader.loadNanopubsFile.getAbsolutePath());
        File loadUrisFile = new File(LocalNanopubLoader.loadUrisFile.getAbsolutePath());
        loadNanopubsFile.createNewFile();
        loadUrisFile.createNewFile();

        try (MockedStatic<LocalNanopubLoader> mockedLoader = mockStatic(LocalNanopubLoader.class, CALLS_REAL_METHODS)) {
            mockedLoader.when(LocalNanopubLoader::load).thenAnswer(invocation -> null);
            assertTrue(LocalNanopubLoader.init());
        }
    }

    @Test
    void loadWithNoUrisFile() {
        File loadUrisFile = new File(LocalNanopubLoader.loadUrisFile.getAbsolutePath());
        assertFalse(loadUrisFile.exists());

        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(errContent));

        LocalNanopubLoader.load();

        System.setErr(originalErr);
        assertTrue(errContent.toString().contains("No local nanopub URI file found."));
    }

    @Test
    void loadWithNoNanopubsFile() {
        File loadNanopubsFile = new File(LocalNanopubLoader.loadNanopubsFile.getAbsolutePath());
        assertFalse(loadNanopubsFile.exists());

        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(errContent));

        LocalNanopubLoader.load();

        System.setErr(originalErr);
        assertTrue(errContent.toString().contains("No local nanopub file found."));
    }

    @Test
    void loadWithUrisFile() throws IOException {
        File directory = new File(loadDirectory);
        if (!directory.exists()) {
            boolean created = directory.mkdirs();
            if (!created) {
                throw new IOException("Failed to create directory: " + directory.getAbsolutePath());
            }
        }
        File loadUrisFile = new File(LocalNanopubLoader.loadUrisFile.getAbsolutePath());
        loadUrisFile.createNewFile();
        try (PrintStream out = new PrintStream(loadUrisFile)) {
            out.println("https://w3id.org/np/RAAADa2kwJXzW0NH3yqeZ8YAJHzFQTg2NRJV-R2u5LTbU");
            out.println("https://w3id.org/np/RAAADa2kwJXzW0NH3yqeZ8YAJHzFQTg2NRJV-R2u5LTbU");
        }

        try (MockedStatic<NanopubLoader> mockedLoader = mockStatic(NanopubLoader.class)) {
            mockedLoader.when(() -> NanopubLoader.load(anyString())).thenAnswer(invocation -> null);
            LocalNanopubLoader.load();
            mockedLoader.verify(() -> NanopubLoader.load(anyString()), times(2));
        }
    }

}
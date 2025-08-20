package com.knowledgepixels.query;

import org.eclipse.rdf4j.repository.Repository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TripleStoreTest {

    @Test
    void getRepoConnectionWithValidRepo() {
        TripleStore mock = mock(TripleStore.class);
        Repository repository = mock(Repository.class);
        when(mock.getRepository("test")).thenReturn(repository);
        assertEquals(repository.getConnection(), mock.getRepoConnection("test"));
    }

    @Test
    void getRepoConnectionWithInvalidRepo() {
        TripleStore mock = mock(TripleStore.class);
        when(mock.getRepository("test")).thenReturn(null);
        assertNull(mock.getRepoConnection("test"));
    }

}
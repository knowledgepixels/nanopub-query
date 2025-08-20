package com.knowledgepixels.query;

import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatusControllerTest {

    @BeforeEach
    void setUp() {
        StatusController.get().resetForTest();
    }

    @Test
    void getNotNull() {
        assertNotNull(StatusController.get());
    }

    @Test
    void getStateWhenNotInitialized() {
        StatusController.LoadingStatus status = StatusController.get().getState();
        assertEquals(StatusController.LoadingStatus.of(null, -1), status);
    }

    @Test
    void equalsLoadingStatus() {
        StatusController.LoadingStatus status1 = StatusController.LoadingStatus.of(StatusController.State.READY, 10);
        StatusController.LoadingStatus status2 = StatusController.LoadingStatus.of(StatusController.State.READY, 10);
        assertEquals(status1, status2);

        // don't change the order of the parameters otherwise the .equals() method is not called
        assertNotEquals(status1, null);
        assertNotEquals(status1, new Object());

        StatusController.LoadingStatus status3 = StatusController.LoadingStatus.of(StatusController.State.LAUNCHING, 10);
        assertNotEquals(status1, status3);

        StatusController.LoadingStatus status4 = StatusController.LoadingStatus.of(StatusController.State.READY, 2);
        assertNotEquals(status1, status4);
    }

    @Test
    void hashCodeLoadingStatus() {
        StatusController.LoadingStatus status1 = StatusController.LoadingStatus.of(StatusController.State.READY, 10);
        assertEquals(status1.hashCode(), Objects.hash(StatusController.State.READY, 10));
    }

    @Test
    void initializeWhenAlreadyInitialized() {
        try (MockedStatic<TripleStore> mockedTripleStoreStatic = mockStatic(TripleStore.class)) {
            StatusController controller = StatusController.get();
            mockedTripleStoreStatic.when(TripleStore::get).thenReturn(mock(TripleStore.class));

            when(TripleStore.get().getAdminRepoConnection()).thenReturn(mock(RepositoryConnection.class));
            when(TripleStore.get().getAdminRepoConnection().getValueFactory()).thenReturn(SimpleValueFactory.getInstance());
            when(TripleStore.get().getAdminRepoConnection().getStatements(any(), any(), any(), any())).thenReturn(mock(RepositoryResult.class));

            controller.initialize();
            assertThrows(IllegalStateException.class, controller::initialize);
        }
    }

    /*@Test
    void initializeWithoutStatements() {
        try (MockedStatic<TripleStore> mockedTripleStoreStatic = mockStatic(TripleStore.class)) {
            StatusController controller = StatusController.get();

            mockedTripleStoreStatic.when(TripleStore::get).thenReturn(mock(TripleStore.class));

            when(TripleStore.get().getAdminRepoConnection()).thenReturn(mock(RepositoryConnection.class));
            when(TripleStore.get().getAdminRepoConnection().getValueFactory()).thenReturn(SimpleValueFactory.getInstance());
            when(TripleStore.get().getAdminRepoConnection().getStatements(any(), any(), any(), any())).thenReturn(mock(RepositoryResult.class));

            StatusController.LoadingStatus loadingStatus = controller.getState();
            assertEquals(StatusController.LoadingStatus.of(StatusController.State.LAUNCHING, -1), loadingStatus);
        }
    }*/

    @Test
    void updateState() {
        try (MockedStatic<TripleStore> mockedTripleStoreStatic = mockStatic(TripleStore.class)) {
            StatusController controller = StatusController.get();

            mockedTripleStoreStatic.when(TripleStore::get).thenReturn(mock(TripleStore.class));

            when(TripleStore.get().getAdminRepoConnection()).thenReturn(mock(RepositoryConnection.class));
            when(TripleStore.get().getAdminRepoConnection().getValueFactory()).thenReturn(SimpleValueFactory.getInstance());
            when(TripleStore.get().getAdminRepoConnection().getStatements(any(), any(), any(), any())).thenReturn(mock(RepositoryResult.class));

            controller.initialize();
            controller.updateState(StatusController.State.READY, 10);

            StatusController.LoadingStatus loadingStatus = controller.getState();
            assertEquals(StatusController.LoadingStatus.of(StatusController.State.READY, 10), loadingStatus);
        }
    }

    @Test
    void setReady() {
        try (MockedStatic<TripleStore> mockedTripleStoreStatic = mockStatic(TripleStore.class)) {
            StatusController controller = StatusController.get();

            mockedTripleStoreStatic.when(TripleStore::get).thenReturn(mock(TripleStore.class));

            when(TripleStore.get().getAdminRepoConnection()).thenReturn(mock(RepositoryConnection.class));
            when(TripleStore.get().getAdminRepoConnection().getValueFactory()).thenReturn(SimpleValueFactory.getInstance());
            when(TripleStore.get().getAdminRepoConnection().getStatements(any(), any(), any(), any())).thenReturn(mock(RepositoryResult.class));

            controller.initialize();
            controller.setReady();
            StatusController.LoadingStatus loadingStatus = controller.getState();
            assertEquals(StatusController.State.READY, loadingStatus.state);

            controller.setReady();
            assertEquals(StatusController.State.READY, loadingStatus.state);
        }
    }

    @Test
    void setLoadingInitial() {
        try (MockedStatic<TripleStore> mockedTripleStoreStatic = mockStatic(TripleStore.class)) {
            StatusController controller = StatusController.get();

            mockedTripleStoreStatic.when(TripleStore::get).thenReturn(mock(TripleStore.class));

            when(TripleStore.get().getAdminRepoConnection()).thenReturn(mock(RepositoryConnection.class));
            when(TripleStore.get().getAdminRepoConnection().getValueFactory()).thenReturn(SimpleValueFactory.getInstance());
            when(TripleStore.get().getAdminRepoConnection().getStatements(any(), any(), any(), any())).thenReturn(mock(RepositoryResult.class));

            controller.initialize();
            controller.updateState(StatusController.State.LAUNCHING, 0);
            controller.setLoadingInitial(10);
            StatusController.LoadingStatus loadingStatus = controller.getState();
            assertEquals(loadingStatus, StatusController.LoadingStatus.of(StatusController.State.LOADING_INITIAL, 10));

            controller.updateState(StatusController.State.LOADING_INITIAL, 0);
            controller.setLoadingInitial(10);
            loadingStatus = controller.getState();
            assertEquals(loadingStatus, StatusController.LoadingStatus.of(StatusController.State.LOADING_INITIAL, 10));

            assertThrows(IllegalStateException.class, () -> controller.setLoadingInitial(5));

            controller.updateState(StatusController.State.READY, 0);
            assertThrows(IllegalStateException.class, () -> controller.setLoadingInitial(10));
        }
    }

    @Test
    void setLoadingUpdates() {
        try (MockedStatic<TripleStore> mockedTripleStoreStatic = mockStatic(TripleStore.class)) {
            StatusController controller = StatusController.get();

            mockedTripleStoreStatic.when(TripleStore::get).thenReturn(mock(TripleStore.class));

            when(TripleStore.get().getAdminRepoConnection()).thenReturn(mock(RepositoryConnection.class));
            when(TripleStore.get().getAdminRepoConnection().getValueFactory()).thenReturn(SimpleValueFactory.getInstance());
            when(TripleStore.get().getAdminRepoConnection().getStatements(any(), any(), any(), any())).thenReturn(mock(RepositoryResult.class));

            controller.initialize();
            controller.updateState(StatusController.State.LAUNCHING, 0);
            controller.setLoadingUpdates(10);
            StatusController.LoadingStatus loadingStatus = controller.getState();
            assertEquals(loadingStatus, StatusController.LoadingStatus.of(StatusController.State.LOADING_UPDATES, 10));

            controller.updateState(StatusController.State.LOADING_UPDATES, 0);
            controller.setLoadingUpdates(10);
            loadingStatus = controller.getState();
            assertEquals(loadingStatus, StatusController.LoadingStatus.of(StatusController.State.LOADING_UPDATES, 10));

            controller.updateState(StatusController.State.READY, 0);
            controller.setLoadingUpdates(10);
            loadingStatus = controller.getState();
            assertEquals(loadingStatus, StatusController.LoadingStatus.of(StatusController.State.LOADING_UPDATES, 10));

            controller.updateState(StatusController.State.LOADING_INITIAL, 0);
            assertThrows(IllegalStateException.class, () -> controller.setLoadingUpdates(10));

            assertThrows(IllegalStateException.class, () -> controller.setLoadingUpdates(5));

            controller.updateState(StatusController.State.LAUNCHING, 0);
            assertThrows(IllegalStateException.class, () -> controller.setLoadingUpdates(-1));
        }
    }

}
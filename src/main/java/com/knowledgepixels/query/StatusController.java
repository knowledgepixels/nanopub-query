package com.knowledgepixels.query;

import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.nanopub.vocabulary.NPA;

import java.util.Objects;

/**
 * Class to control the load status of the database.
 */
public class StatusController {

    /**
     * The load states in which the database can be.
     */
    public enum State {
        /**
         * The service is launching.
         */
        LAUNCHING,
        /**
         * The service is loading.
         */
        LOADING_INITIAL,
        /**
         * The service is loading updates.
         */
        LOADING_UPDATES,
        /**
         * The service is ready to serve requests.
         */
        READY,
        /**
         * The service detected a registry reset and is about to resync.
         */
        RESETTING,
    }

    /**
     * Get the singleton instance of the StatusController.
     *
     * @return the StatusController instance
     */
    public static StatusController get() {
        return instance;
    }

    private final static StatusController instance = new StatusController();

    static final IRI HAS_REGISTRY_SETUP_ID =
            SimpleValueFactory.getInstance().createIRI(NPA.NAMESPACE, "hasRegistrySetupId");

    private boolean initialized = false;
    private volatile State state = null;
    private volatile long lastCommittedCounter = -1;
    private volatile Long registrySetupId = null;
    private RepositoryConnection adminRepoConn;

    /**
     * Represents the current status of the service, including the load counter.
     */
    public static class LoadingStatus {

        /**
         * The current state of the service.
         */
        public final State state;

        /**
         * The current load counter.
         */
        public final long loadCounter;

        private LoadingStatus(State state, long loadCounter) {
            this.state = state;
            this.loadCounter = loadCounter;
        }

        /**
         * Create a new LoadingStatus instance.
         *
         * @param state       the current state of the service
         * @param loadCounter the current load counter
         * @return a new LoadingStatus instance
         */
        public static LoadingStatus of(State state, long loadCounter) {
            return new LoadingStatus(state, loadCounter);
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            LoadingStatus that = (LoadingStatus) o;
            return loadCounter == that.loadCounter && state == that.state;
        }

        @Override
        public int hashCode() {
            return Objects.hash(state, loadCounter);
        }

    }

    /**
     * Initialize the StatusController, fetching the last known state from the DB.
     * This must be called right after service startup, before loading any nanopubs.
     *
     * @return the current state and the last committed counter
     */
    public LoadingStatus initialize() {
        synchronized (this) {
            if (initialized) {
                throw new IllegalStateException("Already initialized");
            }
            state = State.LAUNCHING;
            adminRepoConn = TripleStore.get().getAdminRepoConnection();
            // Serializable, as the service state needs to be strictly consistent
            adminRepoConn.begin(IsolationLevels.SERIALIZABLE);
            // Fetch the state from the DB
            try (var statements = adminRepoConn.getStatements(NPA.THIS_REPO, NPA.HAS_STATUS, null, NPA.GRAPH)) {
                if (!statements.hasNext()) {
                    adminRepoConn.add(NPA.THIS_REPO, NPA.HAS_STATUS, stateAsLiteral(state), NPA.GRAPH);
                } else {
                    var stateStatement = statements.next();
                    state = State.valueOf(stateStatement.getObject().stringValue());
                }
            }
            // Fetch the load counter from the DB
            try (var statements = adminRepoConn.getStatements(NPA.THIS_REPO, NPA.HAS_REGISTRY_LOAD_COUNTER, null, NPA.GRAPH)) {
                if (!statements.hasNext()) {
                    adminRepoConn.add(NPA.THIS_REPO, NPA.HAS_REGISTRY_LOAD_COUNTER, adminRepoConn.getValueFactory().createLiteral(-1L), NPA.GRAPH);
                } else {
                    var counterStatement = statements.next();
                    var stringVal = counterStatement.getObject().stringValue();
                    lastCommittedCounter = Long.parseLong(stringVal);
                }
            }
            // Fetch the registry setup ID from the DB
            try (var statements = adminRepoConn.getStatements(NPA.THIS_REPO, HAS_REGISTRY_SETUP_ID, null, NPA.GRAPH)) {
                if (statements.hasNext()) {
                    var setupIdStatement = statements.next();
                    registrySetupId = Long.parseLong(setupIdStatement.getObject().stringValue());
                }
                adminRepoConn.commit();
            } catch (Exception e) {
                if (adminRepoConn.isActive()) {
                    try {
                        adminRepoConn.rollback();
                    } catch (Exception rollbackException) {
                        // Transaction may not be registered on server (e.g., already committed, timed out, or connection reset)
                        // Log the rollback failure but don't mask the original exception
                    }
                }
                throw new RuntimeException(e);
            }
            initialized = true;
            return getState();
        }
    }

    /**
     * Get the current state of the service.
     *
     * @return the current state and the last committed counter
     */
    public LoadingStatus getState() {
        return LoadingStatus.of(state, lastCommittedCounter);
    }

    /**
     * Transition the service to the LOADING_INITIAL state and update the load counter.
     * This should be called in two situations:
     * - By the main loading thread (after calling initialize()) to start loading the initial nanopubs.
     * - By the initial nanopub loader, as it processes the initial nanopubs.
     *
     * @param loadCounter the new load counter
     */
    public void setLoadingInitial(long loadCounter) {
        synchronized (this) {
            if (state != State.LAUNCHING && state != State.LOADING_INITIAL && state != State.RESETTING) {
                throw new IllegalStateException("Cannot transition to LOADING_INITIAL, as the " + "current state is " + state);
            }
            if (state != State.RESETTING && lastCommittedCounter > loadCounter) {
                throw new IllegalStateException("Cannot update the load counter from " + lastCommittedCounter + " to " + loadCounter);
            }
            updateState(State.LOADING_INITIAL, loadCounter);
        }
    }

    /**
     * Transition the service to the LOADING_UPDATES state and update the load counter.
     * This should be called by the updates loader, when it starts processing new nanopubs, or
     * when it finishes processing a batch of nanopubs.
     *
     * @param loadCounter the new load counter
     */
    public void setLoadingUpdates(long loadCounter) {
        synchronized (this) {
            if (state != State.LAUNCHING && state != State.LOADING_UPDATES && state != State.READY) {
                throw new IllegalStateException("Cannot transition to LOADING_UPDATES, as the " + "current state is " + state);
            }
            if (lastCommittedCounter > loadCounter) {
                throw new IllegalStateException("Cannot update the load counter from " + lastCommittedCounter + " to " + loadCounter);
            }
            // Idempotence guard: skip the admin-repo rewrite if the state and counter
            // are already where we'd set them. A no-op loop iteration of the updates
            // loader otherwise re-writes the same two triples hundreds of times an
            // hour over a long idle tail, each write growing the admin-repo LMDB via
            // copy-on-write.
            if (state == State.LOADING_UPDATES && lastCommittedCounter == loadCounter) {
                return;
            }
            updateState(State.LOADING_UPDATES, loadCounter);
        }
    }

    /**
     * Transition the service to the READY state.
     * This should be called by the loaders, after they finish their work.
     */
    public void setReady() {
        synchronized (this) {
            if (state != State.READY) {
                updateState(State.READY, lastCommittedCounter);
            }
        }
    }

    /**
     * Transition the service to the RESETTING state, resetting the load counter to -1.
     * This is triggered when a registry reset is detected (setupId changed or counter decreased).
     */
    public void setResetting() {
        synchronized (this) {
            if (state != State.READY && state != State.LOADING_UPDATES) {
                throw new IllegalStateException("Cannot transition to RESETTING, as the " + "current state is " + state);
            }
            updateState(State.RESETTING, -1);
        }
    }

    /**
     * Get the persisted registry setup ID.
     *
     * @return the registry setup ID, or null if not yet known
     */
    public Long getRegistrySetupId() {
        // Lock-free read: field is volatile, writers still hold synchronized(this)
        // so there are no concurrent writers. applyGlobalHeaders in MainVerticle
        // calls this on every inbound request on the Vert.x event loop — blocking
        // here behind updateState's admin-repo transaction was a BlockedThreadChecker
        // hazard. The DB-commit-first order in the setter (setRegistrySetupId)
        // means a reader can observe the previous value for the few ms between DB
        // commit and field assignment; no caller depends on stronger consistency.
        return registrySetupId;
    }

    /**
     * Persist a new registry setup ID to the admin repo.
     *
     * @param setupId the new setup ID
     */
    public void setRegistrySetupId(long setupId) {
        synchronized (this) {
            try {
                adminRepoConn.begin(IsolationLevels.SERIALIZABLE);
                adminRepoConn.remove(NPA.THIS_REPO, HAS_REGISTRY_SETUP_ID, null, NPA.GRAPH);
                adminRepoConn.add(NPA.THIS_REPO, HAS_REGISTRY_SETUP_ID, adminRepoConn.getValueFactory().createLiteral(setupId), NPA.GRAPH);
                adminRepoConn.commit();
                registrySetupId = setupId;
            } catch (Exception e) {
                if (adminRepoConn.isActive()) {
                    try {
                        adminRepoConn.rollback();
                    } catch (Exception rollbackException) {
                        // Log the rollback failure but don't mask the original exception
                    }
                }
                throw new RuntimeException(e);
            }
        }
    }

    void updateState(State newState, long loadCounter) {
        synchronized (this) {
            // Update in-memory state first so getState() (called from the event loop) never blocks
            state = newState;
            lastCommittedCounter = loadCounter;
            try {
                // Serializable, as the service state needs to be strictly consistent
                adminRepoConn.begin(IsolationLevels.SERIALIZABLE);
                adminRepoConn.remove(NPA.THIS_REPO, NPA.HAS_STATUS, null, NPA.GRAPH);
                adminRepoConn.add(NPA.THIS_REPO, NPA.HAS_STATUS, stateAsLiteral(newState), NPA.GRAPH);
                adminRepoConn.remove(NPA.THIS_REPO, NPA.HAS_REGISTRY_LOAD_COUNTER, null, NPA.GRAPH);
                adminRepoConn.add(NPA.THIS_REPO, NPA.HAS_REGISTRY_LOAD_COUNTER, adminRepoConn.getValueFactory().createLiteral(loadCounter), NPA.GRAPH);
                adminRepoConn.commit();
            } catch (Exception e) {
                if (adminRepoConn.isActive()) {
                    try {
                        adminRepoConn.rollback();
                    } catch (Exception rollbackException) {
                        // Transaction may not be registered on server (e.g., already committed, timed out, or connection reset)
                        // Log the rollback failure but don't mask the original exception
                    }
                }
                throw new RuntimeException(e);
            }
        }
    }

    private Literal stateAsLiteral(State s) {
        return adminRepoConn.getValueFactory().createLiteral(s.toString());
    }

    /**
     * Reset the StatusController for testing purposes.
     * This will clear the state and allow re-initialization.
     */
    void resetForTest() {
        synchronized (this) {
            initialized = false;
            state = null;
            lastCommittedCounter = -1;
            registrySetupId = null;
            adminRepoConn = null;
        }
    }

}

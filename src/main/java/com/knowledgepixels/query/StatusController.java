package com.knowledgepixels.query;

import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.repository.RepositoryConnection;

public class StatusController {
    public enum State {
        LAUNCHING,
        LOADING_INITIAL,
        LOADING_UPDATES,
        READY,
    }

    /**
     * Represents the current status of the service, including the load counter.
     */
    public static class LoadingStatus {
        public final State state;
        public final long loadCounter;

        private LoadingStatus(State state, long loadCounter) {
            this.state = state;
            this.loadCounter = loadCounter;
        }

        public static LoadingStatus of(State state, long loadCounter) {
            return new LoadingStatus(state, loadCounter);
        }
    }

    public static StatusController get() {
        return instance;
    }

    private final static StatusController instance = new StatusController();
    
    private boolean initialized = false;
    private State state = null;
    private long lastCommittedCounter = -1;
    private RepositoryConnection adminRepoConn;

    /**
     * Initialize the StatusController, fetching the last known state from the DB.
     * This must be called right after service startup, before loading any nanopubs.
     * @return the current state and the last committed counter
     */
    public LoadingStatus initialize() {
        synchronized (this) {
            if (initialized) {
                throw new IllegalStateException("Already initialized");
            }
            state = State.LAUNCHING;
            adminRepoConn = TripleStore.get().getAdminRepoConnection();
            adminRepoConn.begin(IsolationLevels.SERIALIZABLE);
            // Fetch the state from the DB
            try (var statements = adminRepoConn.getStatements(
                    TripleStore.THIS_REPO_ID,
                    TripleStore.HAS_STATUS,
                    null,
                    NanopubLoader.ADMIN_GRAPH
            )) {
                if (!statements.hasNext()) {
                    adminRepoConn.add(
                            TripleStore.THIS_REPO_ID,
                            TripleStore.HAS_STATUS,
                            stateAsLiteral(state),
                            NanopubLoader.ADMIN_GRAPH
                    );
                } else {
                    var stateStatement = statements.next();
                    state = State.valueOf(stateStatement.getObject().stringValue());
                }
            }
            // Fetch the load counter from the DB
            try (var statements = adminRepoConn.getStatements(
                    TripleStore.THIS_REPO_ID,
                    TripleStore.HAS_REGISTRY_LOAD_COUNTER,
                    null,
                    NanopubLoader.ADMIN_GRAPH
            )) {
                if (!statements.hasNext()) {
                    adminRepoConn.add(
                            TripleStore.THIS_REPO_ID,
                            TripleStore.HAS_REGISTRY_LOAD_COUNTER,
                            adminRepoConn.getValueFactory().createLiteral(-1L),
                            NanopubLoader.ADMIN_GRAPH
                    );
                } else {
                    var counterStatement = statements.next();
                    var stringVal = counterStatement.getObject().stringValue();
                    lastCommittedCounter = Long.parseLong(stringVal);
                }
            }
            adminRepoConn.commit();
            initialized = true;
            return getState();
        }
    }

    /**
     * Get the current state of the service.
     * @return the current state and the last committed counter
     */
    public LoadingStatus getState() {
        synchronized (this) {
            return LoadingStatus.of(state, lastCommittedCounter);
        }
    }

    /**
     * Transition the service to the LOADING_INITIAL state and update the load counter.
     * This should be called in two situations:
     *  - By the main loading thread (after calling initialize()) to start loading the initial nanopubs.
     *  - By the initial nanopub loader, as it processes the initial nanopubs.
     * @param loadCounter the new load counter
     */
    public void setLoadingInitial(long loadCounter) {
        synchronized (this) {
            if (state != State.LAUNCHING && state != State.LOADING_INITIAL) {
                throw new IllegalStateException("Cannot transition to LOADING_INITIAL, as the " +
                        "current state is " + state);
            }
            if (lastCommittedCounter > loadCounter) {
                throw new IllegalStateException("Cannot update the load counter from " +
                        lastCommittedCounter + " to " + loadCounter);
            }
            updateState(State.LOADING_INITIAL, loadCounter);
        }
    }

    /**
     * Transition the service to the LOADING_UPDATES state and update the load counter.
     * This should be called by the updates loader, when it starts processing new nanopubs, or
     * when it finishes processing a batch of nanopubs.
     * @param loadCounter the new load counter
     */
    public void setLoadingUpdates(long loadCounter) {
        synchronized (this) {
            if (state != State.LAUNCHING && state != State.LOADING_UPDATES && state != State.READY) {
                throw new IllegalStateException("Cannot transition to LOADING_UPDATES, as the " +
                        "current state is " + state);
            }
            if (lastCommittedCounter > loadCounter) {
                throw new IllegalStateException("Cannot update the load counter from " +
                        lastCommittedCounter + " to " + loadCounter);
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
            if (state == State.READY) {
                return; // Nothing to do
            }
            updateState(State.READY, lastCommittedCounter);
        }
    }

    private void updateState(State newState, long loadCounter) {
        synchronized (this) {
            adminRepoConn.begin(IsolationLevels.SERIALIZABLE);
            adminRepoConn.remove(
                    TripleStore.THIS_REPO_ID,
                    TripleStore.HAS_STATUS,
                    null,
                    NanopubLoader.ADMIN_GRAPH
            );
            adminRepoConn.add(
                    TripleStore.THIS_REPO_ID,
                    TripleStore.HAS_STATUS,
                    stateAsLiteral(newState),
                    NanopubLoader.ADMIN_GRAPH
            );
            adminRepoConn.remove(
                    TripleStore.THIS_REPO_ID,
                    TripleStore.HAS_REGISTRY_LOAD_COUNTER,
                    null,
                    NanopubLoader.ADMIN_GRAPH
            );
            adminRepoConn.add(
                    TripleStore.THIS_REPO_ID,
                    TripleStore.HAS_REGISTRY_LOAD_COUNTER,
                    adminRepoConn.getValueFactory().createLiteral(loadCounter),
                    NanopubLoader.ADMIN_GRAPH
            );
            adminRepoConn.commit();
            state = newState;
            lastCommittedCounter = loadCounter;
        }
    }

    private Literal stateAsLiteral(State s) {
        return adminRepoConn.getValueFactory().createLiteral(s.toString());
    }
}

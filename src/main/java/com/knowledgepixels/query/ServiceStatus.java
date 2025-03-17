package com.knowledgepixels.query;

public class ServiceStatus {
    public enum State {
        LAUNCHING,
        LOADING_INITIAL,
        LOADING_UPDATES,
        READY,
    }

    // TODO: the state machine is currently implemented as an in-memory variable.
    //   Persist it in the DB!
    //   https://github.com/knowledgepixels/nanopub-query/issues/13

    private final static Object sync = new Object();
    private static State state = State.LAUNCHING;

    public static State getState() {
        return state;
    }

    /**
     * Check if the service is in a given state.
     * @param s the state to check
     * @return true if the service is in the given state, false otherwise
     */
    public static boolean isInState(State s) {
        synchronized (sync) {
            return state == s;
        }
    }

    /**
     * Transition to a new state.
     * This should be called by JellyNanopubLoader.
     *
     * @param newState the new state to transition to
     * @return true if the transition was successful, false otherwise
     */
    public static boolean transitionTo(State newState) {
        synchronized (sync) {
            if (state == State.LAUNCHING && newState == State.LOADING_INITIAL) {
                state = newState;
                return true;
            }
            if (state == State.LOADING_INITIAL && newState == State.READY) {
                state = newState;
                return true;
            }
            if (state == State.LOADING_UPDATES && newState == State.READY) {
                state = newState;
                return true;
            }
            if (state == State.READY && newState == State.LOADING_UPDATES) {
                state = newState;
                return true;
            }
            return false;
        }
    }
}

package com.knowledgepixels.query.exception;

/**
 * Exception thrown when a transient error occurs while loading nanopubs.
 * Retry the operation after a short delay.
 */
public class TransientNanopubLoadingException extends RuntimeException {
    public TransientNanopubLoadingException(String message) {
        super(message);
    }

    public TransientNanopubLoadingException(String message, Throwable cause) {
        super(message, cause);
    }
}

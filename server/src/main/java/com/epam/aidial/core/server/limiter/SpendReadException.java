package com.epam.aidial.core.server.limiter;

/**
 * The caller's spend record could not be read, as opposed to a failure of any other part of the limit check.
 */
public class SpendReadException extends RuntimeException {

    public SpendReadException(Throwable cause) {
        super("Failed to read the spend record", cause);
    }
}

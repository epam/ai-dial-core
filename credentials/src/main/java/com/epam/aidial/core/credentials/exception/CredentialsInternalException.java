package com.epam.aidial.core.credentials.exception;

public class CredentialsInternalException extends RuntimeException {

    public CredentialsInternalException() {
    }

    public CredentialsInternalException(String message) {
        super(message);
    }

    public CredentialsInternalException(String message, Throwable cause) {
        super(message, cause);
    }
}

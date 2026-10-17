package com.epam.aidial.core.translator;

/**
 * The request carries something the translation cannot express, so sending it on would have the model
 * answer a different request. The message names what it was, for the client to change.
 */
public class UntranslatableRequestException extends RuntimeException {

    public UntranslatableRequestException(String message) {
        super(message);
    }
}

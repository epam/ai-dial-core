package com.epam.aidial.cli.exception;

public class NetworkException extends CliException {
    public NetworkException(String message, Throwable cause) {
        super(message, 1, cause);
    }
}

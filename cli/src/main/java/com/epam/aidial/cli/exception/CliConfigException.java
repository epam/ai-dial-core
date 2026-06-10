package com.epam.aidial.cli.exception;

public class CliConfigException extends CliException {
    public CliConfigException(String message, Throwable cause) {
        super(message, 2, cause);
    }
}

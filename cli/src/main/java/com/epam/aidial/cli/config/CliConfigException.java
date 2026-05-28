package com.epam.aidial.cli.config;

import com.epam.aidial.cli.CliException;

public class CliConfigException extends CliException {
    public CliConfigException(String message, Throwable cause) {
        super(message, 2, cause);
    }
}

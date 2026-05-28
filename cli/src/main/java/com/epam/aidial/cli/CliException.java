package com.epam.aidial.cli;

import com.epam.aidial.cli.http.CliHttpClient;

public class CliException extends RuntimeException {

    private final int exitCode;

    public CliException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public CliException(String message, int exitCode, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }

    public static CliException validation(String message) {
        return new CliException(message, 2);
    }

    public static CliException network(String message) {
        return new CliException(message, 1);
    }

    public static CliException alreadyExists(String canonicalId) {
        return new CliException("Already exists: " + canonicalId, 5);
    }

    public static CliException httpError(int status, String body, String path) {
        return new CliException(EntityReader.formatHttpError(status, body, path), CliHttpClient.toExitCode(status));
    }
}

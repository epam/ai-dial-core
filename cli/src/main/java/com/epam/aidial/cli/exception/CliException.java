package com.epam.aidial.cli.exception;

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

    public static CliException jsonProcessing(String message) {
        return new CliException(message, 1);
    }

    public static CliException alreadyExists(String canonicalId) {
        return new CliException("Already exists: " + canonicalId, 5);
    }

    public static CliException httpError(int status, String body, String path) {
        return new CliException(formatHttpError(status, body, path), toExitCode(status));
    }

    public static int toExitCode(int status) {
        if (status >= 200 && status < 300) {
            return 0;
        }
        if (status == 401 || status == 403) {
            return 3;
        }
        if (status == 404) {
            return 4;
        }
        if (status == 409) {
            return 5;
        }
        if (status == 412) {
            return 6;
        }
        if (status == 400 || status == 422) {
            return 2;
        }
        return 1;
    }

    private static String formatHttpError(int status, String body, String requestPath) {
        String identifier = friendlyIdentifier(requestPath);
        String trimmed = (body == null) ? "" : body.strip();
        return switch (status) {
            case 404 -> "Not found: " + identifier;
            case 409 -> "Already exists: " + identifier
                    + (trimmed.isEmpty() ? "" : " — " + trimmed);
            case 412 -> "Stale ETag: " + identifier
                    + (trimmed.isEmpty() ? "" : " — " + trimmed);
            default -> "HTTP " + status + (trimmed.isEmpty() ? "" : " " + trimmed);
        };
    }

    private static String friendlyIdentifier(String requestPath) {
        if (requestPath == null) {
            return "(unknown)";
        }
        String stripped = requestPath.startsWith("/v1/") ? requestPath.substring(4) : requestPath;
        int query = stripped.indexOf('?');
        if (query >= 0) {
            stripped = stripped.substring(0, query);
        }
        return stripped.isBlank() ? "(unknown)" : stripped;
    }
}

package com.epam.aidial.core.server.service;

public class PollStatusException extends RuntimeException {
    private final int statusCode;

    PollStatusException(int statusCode, String dialResponseId) {
        super("Unexpected status " + statusCode + " from upstream for background job " + dialResponseId);
        this.statusCode = statusCode;
    }

    int getStatusCode() {
        return statusCode;
    }
}

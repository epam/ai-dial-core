package com.epam.aidial.core.server.service;

/**
 * An export was refused because a bucket lock is held by another export or a writer. Nothing was written.
 */
public class AdminExportBusyException extends RuntimeException {

    public AdminExportBusyException(String message) {
        super(message);
    }
}

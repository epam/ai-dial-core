package com.epam.aidial.core.server.service;

/**
 * A system export was refused because a bucket lock is held by another system export or a writer. Nothing was written.
 */
public class AdminSystemExportBusyException extends RuntimeException {

    public AdminSystemExportBusyException(String message) {
        super(message);
    }
}

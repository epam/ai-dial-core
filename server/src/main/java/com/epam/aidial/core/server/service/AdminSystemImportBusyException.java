package com.epam.aidial.core.server.service;

/**
 * A system import was refused because another system import already holds the guard. The body was not read, and
 * nothing in {@code public/} or {@code platform/} was touched.
 */
public class AdminSystemImportBusyException extends RuntimeException {

    public AdminSystemImportBusyException(String message) {
        super(message);
    }
}

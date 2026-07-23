package com.epam.aidial.core.server.util;

public class CatalogSchemaProcessingException extends RuntimeException {
    public CatalogSchemaProcessingException(String message, Throwable cause) {
        super(message, cause);
    }

    public CatalogSchemaProcessingException(String message) {
        super(message);
    }
}

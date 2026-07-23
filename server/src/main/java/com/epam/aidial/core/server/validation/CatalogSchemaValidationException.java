package com.epam.aidial.core.server.validation;

import com.networknt.schema.ValidationMessage;
import lombok.Getter;

import java.util.Set;

@Getter
public class CatalogSchemaValidationException extends RuntimeException {
    public Set<ValidationMessage> validationMessages = Set.of();

    public CatalogSchemaValidationException(String message, Set<ValidationMessage> validationMessages) {
        super(message);
        this.validationMessages = validationMessages;
    }

    public CatalogSchemaValidationException(String message) {
        super(message);
    }
}

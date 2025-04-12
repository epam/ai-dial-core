package com.epam.aidial.core.server.validation;

import com.networknt.schema.ValidationMessage;
import lombok.Getter;

import java.util.Set;

@Getter
public class ApplicationTypeSchemaValidationException extends RuntimeException {
    public Set<ValidationMessage> validationMessages = Set.of();

    public ApplicationTypeSchemaValidationException(String message, Set<ValidationMessage> validationMessages) {
        super(message);
        this.validationMessages = validationMessages;
    }

    public ApplicationTypeSchemaValidationException(String message) {
        super(message);
    }
}

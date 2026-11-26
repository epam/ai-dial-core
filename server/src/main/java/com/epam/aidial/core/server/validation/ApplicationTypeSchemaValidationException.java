package com.epam.aidial.core.server.validation;

import com.networknt.schema.ValidationMessage;
import lombok.Getter;

import java.util.Set;
import java.util.stream.Collectors;

@Getter
public class ApplicationTypeSchemaValidationException extends RuntimeException {
    private final Set<String> validationMessages;

    public ApplicationTypeSchemaValidationException(String message, Set<ValidationMessage> validationMessages) {
        super(message);
        this.validationMessages = validationMessages.stream()
                .map(ValidationMessage::getMessage)
                .collect(Collectors.toUnmodifiableSet());
    }

    public ApplicationTypeSchemaValidationException(String message) {
        super(message);
        this.validationMessages = Set.of(message);
    }
}

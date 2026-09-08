package com.epam.aidial.core.server.data.config.apply;

public record ValidationResult(String entityId, ValidationStatus status, String error) {}
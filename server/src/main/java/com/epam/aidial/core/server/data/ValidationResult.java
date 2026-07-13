package com.epam.aidial.core.server.data;

public record ValidationResult(String entityId, ValidationStatus status, String error) {}
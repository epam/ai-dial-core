package com.epam.aidial.core.server.data.config.manifest;

public record ValidationResult(String entityId, ValidationStatus status, String error) {}
package com.epam.aidial.core.server.data.config.manifest;

public record AdminApplyResult(String entityId, AdminApplyStatus status, String error) {}
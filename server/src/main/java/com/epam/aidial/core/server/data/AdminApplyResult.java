package com.epam.aidial.core.server.data;

public record AdminApplyResult(String entityId, AdminApplyStatus status, String error) {}
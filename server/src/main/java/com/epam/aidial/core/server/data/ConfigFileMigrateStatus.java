package com.epam.aidial.core.server.data;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ConfigFileMigrateStatus {
    MIGRATED,
    WOULD_MIGRATE,
    SKIPPED,
    WOULD_SKIP,
    FAILED,
    WOULD_FAIL;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}

package com.epam.aidial.core.server.data;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ConfigFileMigrateStatus {
    /** Entity was written to blob storage. */
    MIGRATED,
    /** Dry run: entity passed validation and would have been written. */
    WOULD_MIGRATE,
    /** Entity already exists in blob storage; nothing was written. */
    SKIPPED,
    /** Dry run: entity already exists in blob storage; nothing would be written. */
    WOULD_SKIP,
    /** Entity failed validation or write; nothing was written. */
    FAILED,
    /** Dry run: entity failed validation; nothing would be written. */
    WOULD_FAIL;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}

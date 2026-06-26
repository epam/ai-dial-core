package com.epam.aidial.core.server.config;

import com.epam.aidial.core.storage.resource.ResourceTypes;
import lombok.Getter;

import java.util.List;

/**
 * Raised by {@link ConfigPostProcessor}'s semantic pass when an individual entity
 * violates a runtime invariant. {@link MergedConfigStore} catches and routes the
 * exception per the {@code config.reload.onInvalidEntity} setting: under
 * {@code abort} it propagates and the rebuild is rolled back; under {@code skip}
 * the offender is recorded in the invalid-entity sibling store and dropped from
 * the merged {@code Config} (design 02 §4.1).
 *
 * <p>{@code mapKey} is the entry's key as iterated — the canonical ID for blob
 * entries ({@code models/platform/gpt-4}) and the simple name for file-defined
 * entries.
 */
@Getter
public class InvalidEntityException extends RuntimeException {

    private final ResourceTypes type;
    private final String mapKey;
    private final List<ValidationWarning> warnings;

    public InvalidEntityException(ResourceTypes type, String mapKey, List<ValidationWarning> warnings) {
        super(warnings.isEmpty() ? "Invalid entity: " + mapKey : warnings.get(0).getMessage());
        this.type = type;
        this.mapKey = mapKey;
        this.warnings = warnings;
    }
}

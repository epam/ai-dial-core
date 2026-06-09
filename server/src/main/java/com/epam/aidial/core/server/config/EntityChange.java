package com.epam.aidial.core.server.config;

import com.epam.aidial.core.storage.resource.ResourceTypes;

import javax.annotation.Nullable;

/**
 * One mutation entry for {@link MergedConfigStore#applyBatch}. A {@code null}
 * {@code decryptedEntity} signals delete. For non-null entities the caller is
 * responsible for decrypting in-place before the call (same contract as the
 * single-entity {@code applyEntityWrite} path).
 */
public record EntityChange(ResourceTypes type, String canonicalId, @Nullable Object decryptedEntity) {
}

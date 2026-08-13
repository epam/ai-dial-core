package com.epam.aidial.core.server.config;

import com.epam.aidial.core.storage.resource.ResourceTypes;

import javax.annotation.Nullable;

/**
 * One mutation entry for {@link MergedConfigStore#applyBatch}. A {@code null}
 * {@code decryptedEntity} signals delete. For non-null entities the caller is
 * responsible for decrypting in-place before the call (same contract as the
 * single-entity {@code applyEntityWrite} path).
 *
 * <p>{@code mapKey} is the key used in {@code Config}'s in-memory type-map — the short name
 * for models/interceptors/roles/applications/toolsets, the decoded {@code $id} for schema types,
 * and the canonical id for all other types (keys, routes).
 */
public record EntityChange(ResourceTypes type, String mapKey, @Nullable Object decryptedEntity) {
}

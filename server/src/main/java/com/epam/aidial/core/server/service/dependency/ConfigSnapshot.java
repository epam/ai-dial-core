package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.util.Map;
import javax.annotation.Nullable;

/**
 * The configuration that one walk resolves every name against, so that a decision about a deployment is taken on the same publication as the
 * discovery of its references. None of it may be changed.
 *
 * @param merged          the merged configuration
 * @param file            the file-sourced configuration, or {@code null} while the store has none
 * @param invalidEntities the entities that the merged configuration skipped, by type and canonical id
 */
public record ConfigSnapshot(Config merged, @Nullable Config file, Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalidEntities) {
}

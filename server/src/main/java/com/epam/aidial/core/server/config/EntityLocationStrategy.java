package com.epam.aidial.core.server.config;

import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.util.List;
import javax.annotation.Nullable;

/**
 * Resolves where API-managed entities live in blob storage. Pluggable to keep
 * {@link MergedConfigStore} agnostic of bucket/scope policy.
 */
public interface EntityLocationStrategy {

    /** Default scope; value matches the bucket name. */
    String PLATFORM_SCOPE = "platform";

    /**
     * @return the bucket where this {@code (entityType, scope)} pair lives, or
     *         {@code null} if the entity type is not managed through
     *         {@link MergedConfigStore}. Applications and toolsets resolve here only for the
     *         {@code platform} bucket (API-managed, config-equivalent apps/toolsets);
     *         {@code public}-bucket (user-published) applications/toolsets stay outside this
     *         strategy and are served by the existing lazy {@code ApplicationService}/
     *         {@code ToolSetService} per-request resolution instead.
     */
    @Nullable
    String resolveBucket(ResourceTypes entityType, String scope);

    /**
     * @return scopes to merge for the given entity type.
     */
    List<String> listScopes(ResourceTypes entityType);
}

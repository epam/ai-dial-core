package com.epam.aidial.core.server.security;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import lombok.experimental.UtilityClass;

import java.util.Map;
import java.util.Set;

/**
 * Static allowlist of valid {@code (entityType, bucket)} pairs for the Configuration API.
 *
 * <p>The CONFIG_RESOURCE route regex permits any {@code (type, bucket)} combination structurally;
 * this allowlist rejects e.g. {@code GET /v1/keys/public/foo} with 404 (indistinguishable from
 * "entity not found") so an unauthenticated probe cannot enumerate which type/bucket pairs are
 * valid. See {@code 04-security-and-audit.md} §1.2.
 */
@UtilityClass
public class EntityBucketBinding {

    public static final String PLATFORM_BUCKET = "platform";

    /** Sentinel meaning "any non-platform bucket" — admin-shared in {@code public/}, user-owned in user buckets. */
    private static final String USER_BUCKET_WILDCARD = "*";

    private static final Map<String, Set<String>> ALLOWED_BUCKETS = Map.ofEntries(
            Map.entry("models", Set.of(PLATFORM_BUCKET)),
            Map.entry("applications", Set.of(ResourceDescriptor.PUBLIC_BUCKET)),
            Map.entry("toolsets", Set.of(ResourceDescriptor.PUBLIC_BUCKET)),
            Map.entry("schemas", Set.of(PLATFORM_BUCKET)),
            Map.entry(ResourceTypes.FILE.group(), Set.of(ResourceDescriptor.PUBLIC_BUCKET, USER_BUCKET_WILDCARD)),
            Map.entry(ResourceTypes.PROMPT.group(), Set.of(ResourceDescriptor.PUBLIC_BUCKET, USER_BUCKET_WILDCARD)),
            Map.entry(ResourceTypes.CONVERSATION.group(), Set.of(ResourceDescriptor.PUBLIC_BUCKET, USER_BUCKET_WILDCARD)),
            Map.entry("interceptors", Set.of(PLATFORM_BUCKET)),
            Map.entry("roles", Set.of(PLATFORM_BUCKET)),
            Map.entry("keys", Set.of(PLATFORM_BUCKET)),
            Map.entry("routes", Set.of(PLATFORM_BUCKET)),
            Map.entry("settings", Set.of(PLATFORM_BUCKET)));

    public static boolean isAllowed(String entityType, String bucket) {
        Set<String> allowed = ALLOWED_BUCKETS.get(entityType);
        if (allowed == null) {
            return false;
        }
        if (allowed.contains(bucket)) {
            return true;
        }
        return allowed.contains(USER_BUCKET_WILDCARD) && !PLATFORM_BUCKET.equals(bucket);
    }
}

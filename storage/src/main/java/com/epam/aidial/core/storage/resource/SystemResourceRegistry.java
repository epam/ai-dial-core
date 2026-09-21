package com.epam.aidial.core.storage.resource;

/**
 * The platform-internal buckets holding runtime state rather than anyone's content. They belong to no
 * principal, so a layout has to place them somewhere other than the branches it uses for users and
 * projects; this registry is how a layout enumerates them.
 */
public enum SystemResourceRegistry {

    DEPLOYMENT_COST_STATS("deployment_cost_stats"),
    BACKGROUND_JOBS("background_jobs"),
    RESPONSE_MAPPINGS("response_mappings"),
    API_KEY_DATA("api_key_data");

    private final String bucket;

    SystemResourceRegistry(String bucket) {
        this.bucket = bucket;
    }

    public String bucket() {
        return bucket;
    }

    public String location() {
        return bucket + ResourceDescriptor.PATH_SEPARATOR;
    }

    public static boolean isSystemLocation(String location) {
        for (SystemResourceRegistry entry : values()) {
            if (entry.location().equals(location)) {
                return true;
            }
        }
        return false;
    }
}

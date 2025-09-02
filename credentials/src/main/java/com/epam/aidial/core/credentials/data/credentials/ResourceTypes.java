package com.epam.aidial.core.credentials.data.credentials;

import com.epam.aidial.core.storage.resource.ResourceType;

public enum ResourceTypes implements ResourceType {
    CREDENTIALS("credentials", true);

    private final String group;
    private final boolean requireCompression;

    ResourceTypes(String group, boolean requireCompression) {
        this.group = group;
        this.requireCompression = requireCompression;
    }

    @Override
    public String group() {
        return group;
    }

    @Override
    public boolean requireCompression() {
        return requireCompression;
    }

}

package com.epam.aidial.core.storage.resource;

public interface ResourceType {
    String name();

    String group();

    default String urlSegment() {
        return group();
    }

    boolean requireCompression();

    /**
     * @return TTL in milliseconds.
     */
    long ttl();
}

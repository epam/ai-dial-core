package com.epam.aidial.core.storage.resource;

public interface ResourceType {
    String name();

    String group();

    boolean requireCompression();

    /**
     * @return TTL in seconds.
     */
    long ttl();
}

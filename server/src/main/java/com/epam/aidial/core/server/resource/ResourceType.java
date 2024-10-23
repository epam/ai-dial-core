package com.epam.aidial.core.server.resource;

public interface ResourceType {
    String name();

    String group();

    boolean requireCompression();
}

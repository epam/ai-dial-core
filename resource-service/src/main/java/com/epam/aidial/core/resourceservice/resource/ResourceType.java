package com.epam.aidial.core.resourceservice.resource;

public interface ResourceType {
    String name();

    String group();

    boolean requireCompression();
}

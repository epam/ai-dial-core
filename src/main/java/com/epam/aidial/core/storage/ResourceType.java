package com.epam.aidial.core.storage;

public enum ResourceType {
    FILES("files");

    private final String name;

    ResourceType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}

package com.epam.aidial.core.storage;

public enum ResourceType {
    FILE("files");

    private final String folder;

    ResourceType(String folder) {
        this.folder = folder;
    }

    public String getFolder() {
        return folder;
    }
}

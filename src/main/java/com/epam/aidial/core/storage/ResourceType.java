package com.epam.aidial.core.storage;

public enum ResourceType {
    FILE("files"), CONVERSATION("conversations"), PROMPTS("prompts");

    private final String folder;

    ResourceType(String folder) {
        this.folder = folder;
    }

    public String getFolder() {
        return folder;
    }

    public static ResourceType fromFolder(String folder) {
        return switch (folder) {
            case "files" -> FILE;
            case "conversations" -> CONVERSATION;
            case "prompts" -> PROMPTS;
            default -> throw new IllegalArgumentException("Unsupported folder: " + folder);
        };
    }
}

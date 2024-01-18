package com.epam.aidial.core.data;

import lombok.Getter;

@Getter
public enum ResourceType {
    FILE("files"), CONVERSATION("conversations"), PROMPTS("prompts");

    private final String group;

    ResourceType(String group) {
        this.group = group;
    }

    public String getGroup() {
        return group;
    }

    public static ResourceType of(String group) {
        return switch (group) {
            case "files" -> FILE;
            case "conversations" -> CONVERSATION;
            case "prompts" -> PROMPTS;
            default -> throw new IllegalArgumentException("Unsupported group: " + group);
        };
    }
}

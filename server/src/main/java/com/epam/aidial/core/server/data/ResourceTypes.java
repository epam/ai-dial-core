package com.epam.aidial.core.server.data;

import com.epam.aidial.core.server.resource.ResourceType;

public enum ResourceTypes implements ResourceType {
    FILE("files", false), CONVERSATION("conversations", true),
    PROMPT("prompts", true), LIMIT("limits", true),
    SHARED_WITH_ME("shared_with_me", true), SHARED_BY_ME("shared_by_me", true), INVITATION("invitations", true),
    PUBLICATION("publications", true), RULES("rules", true), API_KEY_DATA("api_key_data", true), NOTIFICATION("notifications", true),
    APPLICATION("applications", true), DEPLOYMENT_COST_STATS("deployment_cost_stats", true);

    private final String group;
    private final boolean requireCompression;

    ResourceTypes(String group, boolean requireCompression) {
        this.group = group;
        this.requireCompression = requireCompression;
    }



    public static ResourceTypes of(String group) {
        return switch (group) {
            case "files" -> FILE;
            case "conversations" -> CONVERSATION;
            case "prompts" -> PROMPT;
            case "invitations" -> INVITATION;
            case "publications" -> PUBLICATION;
            case "applications" -> APPLICATION;
            default -> throw new IllegalArgumentException("Unsupported resource type: " + group);
        };
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

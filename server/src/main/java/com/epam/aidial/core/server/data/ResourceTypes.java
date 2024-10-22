package com.epam.aidial.core.server.data;

import com.epam.aidial.core.server.resource.ResourceType;
import lombok.Getter;

@Getter
public enum ResourceTypes {
    FILE("files", true), CONVERSATION("conversations", true), PROMPT("prompts", true), LIMIT("limits", false),
    SHARED_WITH_ME("shared_with_me", false), SHARED_BY_ME("shared_by_me", false), INVITATION("invitations", true),
    PUBLICATION("publications", true), RULES("rules", false), API_KEY_DATA("api_key_data", false), NOTIFICATION("notifications", false),
    APPLICATION("applications", true), DEPLOYMENT_COST_STATS("deployment_cost_stats", false);

    private final String group;
    private final boolean external;
    private ResourceType resourceType;

    ResourceTypes(String group, boolean external) {
        this.group = group;
        this.external = external;
    }

    public void setResourceType(ResourceType type) {
        this.resourceType = type;
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
}

package com.epam.aidial.core.storage.resource;

public enum ResourceTypes implements ResourceType {
    FILE("files", false), CONVERSATION("conversations", true),
    PROMPT("prompts", true), LIMIT("limits", true),
    SHARED_WITH_ME("shared_with_me", true), SHARED_BY_ME("shared_by_me", true), INVITATION("invitations", true),
    PUBLICATION("publications", true), RULES("rules", true), API_KEY_DATA("api_key_data", true), NOTIFICATION("notifications", true),
    APPLICATION("applications", true), DEPLOYMENT_COST_STATS("deployment_cost_stats", true),
    CODE_INTERPRETER_SESSION("code_interpreter_session", true), USER_CONSENT("user_consent", true),
    TOOL_SET("toolsets", true),
    CREDENTIALS("credentials", true),
    ENCRYPTION_KEYS("encryption_keys", true);

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
            case "code_interpreter_session" -> CODE_INTERPRETER_SESSION;
            case "toolsets" -> TOOL_SET;
            case "credentials" -> CREDENTIALS;
            case "encryption_keys" -> ENCRYPTION_KEYS;
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

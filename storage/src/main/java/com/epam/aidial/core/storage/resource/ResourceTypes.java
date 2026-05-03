package com.epam.aidial.core.storage.resource;

import java.util.concurrent.TimeUnit;

public enum ResourceTypes implements ResourceType {

    FILE("files", false, TimeUnit.MINUTES.toMillis(5)),
    CONVERSATION("conversations", true, TimeUnit.MINUTES.toMillis(5)),
    PROMPT("prompts", true, TimeUnit.MINUTES.toMillis(5)),
    LIMIT("limits", true, TimeUnit.MINUTES.toMillis(5)),
    SHARED_WITH_ME("shared_with_me", true, TimeUnit.MINUTES.toMillis(5)),
    SHARED_BY_ME("shared_by_me", true, TimeUnit.MINUTES.toMillis(5)),
    INVITATION("invitations", true, TimeUnit.MINUTES.toMillis(5)),
    PUBLICATION("publications", true, TimeUnit.MINUTES.toMillis(5)),
    RULES("rules", true, TimeUnit.MINUTES.toMillis(5)),
    API_KEY_DATA("api_key_data", true, TimeUnit.MINUTES.toMillis(5)),
    NOTIFICATION("notifications", true, TimeUnit.MINUTES.toMillis(5)),
    APPLICATION("applications", true, TimeUnit.DAYS.toMillis(30)),
    DEPLOYMENT_COST_STATS("deployment_cost_stats", true, TimeUnit.MINUTES.toMillis(5)),
    CODE_INTERPRETER_SESSION("code_interpreter_session", true, TimeUnit.MINUTES.toMillis(5)),
    USER_CONSENT("user_consent", true, TimeUnit.MINUTES.toMillis(5)),
    TOOL_SET("toolsets", true, TimeUnit.DAYS.toMillis(30)),
    CREDENTIALS("credentials", true, TimeUnit.MINUTES.toMillis(5)),
    ENCRYPTION_KEYS("encryption_keys", true, TimeUnit.MINUTES.toMillis(5)),
    CLIENT_CHANNEL("client_channels", true, TimeUnit.HOURS.toMillis(24)),
    MODEL("models", true, Long.MAX_VALUE),
    APP_TYPE_SCHEMA("app_type_schemas", true, Long.MAX_VALUE),
    INTERCEPTOR("interceptors", true, Long.MAX_VALUE),
    ROLE("roles", true, Long.MAX_VALUE),
    PROJECT_KEY("project_keys", true, Long.MAX_VALUE),
    ROUTE("routes", true, Long.MAX_VALUE),
    GLOBAL_SETTINGS("settings", true, Long.MAX_VALUE);

    private final String group;
    private final String urlSegment;
    private final boolean requireCompression;
    private final long ttl;

    ResourceTypes(String group, boolean requireCompression, long ttl) {
        this(group, group, requireCompression, ttl);
    }

    ResourceTypes(String group, String urlSegment, boolean requireCompression, long ttl) {
        this.group = group;
        this.urlSegment = urlSegment;
        this.requireCompression = requireCompression;
        this.ttl = ttl;
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
            case "models" -> MODEL;
            case "app_type_schemas", "schemas" -> APP_TYPE_SCHEMA;
            case "interceptors" -> INTERCEPTOR;
            case "roles" -> ROLE;
            case "project_keys", "keys" -> PROJECT_KEY;
            case "routes" -> ROUTE;
            case "settings" -> GLOBAL_SETTINGS;
            default -> throw new IllegalArgumentException("Unsupported resource type: " + group);
        };
    }

    @Override
    public String group() {
        return group;
    }

    @Override
    public String urlSegment() {
        return urlSegment;
    }

    @Override
    public boolean requireCompression() {
        return requireCompression;
    }

    @Override
    public long ttl() {
        return ttl;
    }


}

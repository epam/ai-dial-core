package com.epam.aidial.core.server.data;

import lombok.Getter;

import java.util.regex.Pattern;

@Getter
public enum RouteTemplate {

    // OpenAI API routes
    LLM_RESPONSES_API(
            "^/+openai/v1/responses$",
            "/openai/v1/responses"
    ),
    LLM_RESPONSES_API_CANCEL(
            "^/+openai/v1/responses/(?<id>[^/]+)/cancel$",
            "/openai/v1/responses/{id}/cancel"
    ),
    LLM_RESPONSES_API_BY_ID(
            "^/+openai/v1/responses/(?<id>[^/]+)$",
            "/openai/v1/responses/{id}"
    ),
    POST_DEPLOYMENT(
            "^/+openai/deployments/(?<id>.+?)/(completions|chat/completions|embeddings)$",
            "/openai/deployments/{id}/{action}"
    ),
    DEPLOYMENT(
            "^/+openai/deployments/(?<id>.+?)$",
            "/openai/deployments/{id}"
    ),
    DEPLOYMENTS(
            "^/+openai/deployments$",
            "/openai/deployments"
    ),

    MODEL(
            "^/+openai/models/(?<id>.+?)$",
            "/openai/models/{id}"
    ),
    MODELS(
            "^/+openai/models$",
            "/openai/models"
    ),

    APPLICATION(
            "^/+openai/applications/(?<id>.+?)$",
            "/openai/applications/{id}"
    ),
    APPLICATIONS(
            "^/+openai/applications$",
            "/openai/applications"
    ),

    // V1 API routes
    FILES(
            "^/v1/files/(?<bucket>[a-zA-Z0-9]+)/(?<path>.*)$",
            "/v1/files/{bucket}/{path}"
    ),
    FILES_METADATA(
            "^/v1/metadata/files/(?<bucket>[a-zA-Z0-9]+)/(?<path>.*)$",
            "/v1/metadata/files/{bucket}/{path}"
    ),

    RESOURCE(
            "^/v1/(conversations|prompts|applications|toolsets)/(?<bucket>[a-zA-Z0-9]+)/(?<path>.*)$",
            "/v1/{resourceType}/{bucket}/{path}"
    ),
    RESOURCE_METADATA(
            "^/v1/metadata/(conversations|prompts|applications|toolsets)/(?<bucket>[a-zA-Z0-9]+)/(?<path>.*)$",
            "/v1/metadata/{resourceType}/{bucket}/{path}"
    ),

    CONFIG_RESOURCE(
            "^/v1/(models|interceptors|roles|keys|routes|schemas|settings)/(?<bucket>[a-zA-Z0-9_-]+)/(?<path>.*)$",
            "/v1/{resourceType}/{bucket}/{path}"
    ),

    CONFIG_RESOURCE_METADATA(
            "^/v1/metadata/(models|interceptors|roles|keys|routes|schemas|settings)/(?<bucket>[a-zA-Z0-9_-]+)/(?<path>.*)$",
            "/v1/metadata/{resourceType}/{bucket}/{path}"
    ),

    CONFIG_HEALTH(
            "^/v1/admin/health/config$",
            "/v1/admin/health/config"
    ),

    ADMIN_FILE_CONFIG(
            "^/v1/admin/config/file/(?<type>models|interceptors|roles|keys|routes|schemas|settings)(?:/(?<name>.+))?$",
            "/v1/admin/config/file/{type}/{name}"
    ),

    CONFIG_VALIDATE(
            "^/v1/admin/validate$",
            "/v1/admin/validate"
    ),

    CONFIG_APPLY(
            "^/v1/admin/apply$",
            "/v1/admin/apply"
    ),

    BUCKET(
            "^/v1/bucket$",
            "/v1/bucket"
    ),

    // Deployment features
    RATE_RESPONSE(
            "^/+v1/(?<id>.+?)/rate$",
            "/v1/{id}/rate"
    ),
    TOKENIZE(
            "^/+v1/deployments/(?<id>.+?)/tokenize$",
            "/v1/deployments/{id}/tokenize"
    ),
    TRUNCATE_PROMPT(
            "^/+v1/deployments/(?<id>.+?)/truncate_prompt$",
            "/v1/deployments/{id}/truncate_prompt"
    ),
    CONFIGURATION(
            "^/+v1/deployments/(?<id>.+?)/configuration$",
            "/v1/deployments/{id}/configuration"
    ),
    DEPLOYMENT_LIMITS(
            "^/v1/deployments/(?<id>.+?)/limits$",
            "/v1/deployments/{id}/limits"
    ),

    DEPLOYMENT_ROUTES(
            "^/+v1/deployments/(?<id>.+)/route(?<routePath>/.+?)$",
            "/v1/deployments/{id}/route/{routePath}"
    ),

    DEPLOYMENT_LISTING(
            "^/+v1/deployments$",
            "/v1/deployments"
    ),

    // Operations
    SHARE_RESOURCE_OPERATIONS(
            "^/v1/ops/resource/share/(create|list|discard|revoke|copy)$",
            "/v1/ops/resource/share/{operation}"
    ),
    INVITATIONS(
            "^/v1/invitations$",
            "/v1/invitations"
    ),
    INVITATION(
            "^/v1/invitations/(?<id>[a-zA-Z0-9]+)$",
            "/v1/invitations/{id}"
    ),
    PUBLICATIONS(
            "^/v1/ops/publication/(list|get|create|delete|approve|reject|update)$",
            "/v1/ops/publication/{operation}"
    ),
    PUBLISHED_RESOURCES(
            "^/v1/ops/publication/resource/list$",
            "/v1/ops/publication/resource/list"
    ),
    PUBLICATION_RULES(
            "^/v1/ops/publication/rule/list$",
            "/v1/ops/publication/rule/list"
    ),
    RESOURCE_OPERATIONS(
            "^/v1/ops/resource/(move|copy|subscribe)$",
            "/v1/ops/resource/{operation}"
    ),
    NOTIFICATIONS(
            "^/v1/ops/notification/(list|delete)$",
            "/v1/ops/notification/{operation}"
    ),

    // Application operations
    APPLICATION_OPERATIONS(
            "^/v1/ops/application/(deploy|undeploy|logs|redeploy)$",
            "/v1/ops/application/{operation}"
    ),

    // Code interpreter
    CODE_INTERPRETER(
            "^/v1/ops/code_interpreter/(open_session|close_session|get_session|execute_code|upload_file|download_file|list_files|transfer_input_file|transfer_output_file)$",
            "/v1/ops/code_interpreter/{operation}"
    ),

    TOOL_SET_CREDENTIALS(
        "^/v1/ops/toolset/(signin|signout)",
        "/v1/ops/toolset/{operation}"
    ),

    EXTERNAL_SERVICE_CREDENTIALS(
        "^/v1/ops/external-service/(signin|signout|credentials)$",
        "/v1/ops/external-service/{operation}"
    ),

    // External-service definition management (admin/app-owner). Hyphenated path per design §13.2;
    // appId is a lazy group (static app name or dynamic {bucket}/{path}) per §13.1.
    EXTERNAL_SERVICES_MANAGEMENT(
        "^/v1/applications/(?<appId>.+?)/external-services$",
        "/v1/applications/{appId}/external-services"
    ),
    EXTERNAL_SERVICE_MANAGEMENT(
        "^/v1/applications/(?<appId>.+?)/external-services/(?<id>[^/]+)$",
        "/v1/applications/{appId}/external-services/{id}"
    ),

    // Other routes
    CONFIG(
            "^/v1/ops/config/reload$",
            "/v1/ops/config/reload"
    ),
    USER_CONSENT(
            "^/v1/consent/(?<id>.+?)$",
            "/v1/consent/{id}"
    ),
    USER_INFO(
            "^/v1/user/info$",
            "/v1/user/info"
    ),
    APP_SCHEMAS(
            "^/v1/application_type_schemas/(schemas|schema|meta_schema)$",
            "/v1/application_type_schemas/{operation}"
    ),
    TOOL_SET(
            "^/+openai/toolsets/(?<id>.+?)$",
            "/openai/toolsets/{id}"
    ),
    TOOL_SETS(
            "^/+openai/toolsets$",
            "/openai/toolsets"
    ),
    TOOL_SET_TOOLS(
            "^/v1/toolset/(?<id>.+?)/tools$",
            "/v1/toolset/{id}/tools"
    ),
    TOOL_SET_ALLOWED_TOOLS(
            "^/v1/toolset/(?<id>.+?)/allowed-tools$",
            "/v1/toolset/{id}/allowed-tools"
    ),
    TOOL_SET_MCP_PROXY(
            "^/v1/toolset/(?<id>.+?)/mcp$",
            "/v1/toolset/{id}/mcp"
    ),
    APPLICATION_MCP_PROXY(
            "^/v1/deployments/(?<id>.+?)/mcp$",
            "/v1/deployments/{id}/mcp"
    ),
    TOOL_SET_PROXY_METADATA(
            "^/\\.well-known/oauth-protected-resource/v1/toolset/(?<id>.+?)/mcp$",
            "/.well-known/oauth-protected-resource/v1/toolset/{id}/mcp"
    ),
    APPLICATION_MCP_PROXY_METADATA(
            "^/\\.well-known/oauth-protected-resource/v1/deployments/(?<id>.+?)/mcp$",
            "/.well-known/oauth-protected-resource/v1/deployments/{id}/mcp"
    ),
    PER_REQUEST_PERMISSION("^/v1/ops/resource/per-request-permissions/(grant|revoke|list)",
            "/v1/ops/resource/per-request-permissions/{operation}"),

    CLIENT_CHANNEL("^/v1/ops/client-channel/(subscribe|report|unsubscribe|interact)",
            "/v1/ops/client-channel/{operation}");

    private final Pattern pattern;
    private final String template;

    RouteTemplate(String regex, String template) {
        this.pattern = Pattern.compile(regex);
        this.template = template;
    }

    /**
     * Check if this route template matches the given path
     */
    public boolean matches(String path) {
        return pattern.matcher(path).matches();
    }

    /**
     * Get the normalized template for metrics
     */
    public String getNormalizedPath() {
        return template;
    }

}

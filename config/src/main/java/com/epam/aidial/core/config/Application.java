package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Application extends Deployment {

    private Function function;

    @JsonAlias({"applicationProperties", "application_properties"})
    private Map<String, Object> applicationProperties; //all custom application properties will land there

    @JsonAlias({"applicationTypeSchemaId", "application_type_schema_id"})
    private URI applicationTypeSchemaId;

    @JsonAlias({"viewerUrl", "viewer_url"})
    private String viewerUrl;

    @JsonAlias({"editorUrl", "editor_url"})
    private String editorUrl;

    private Mcp mcp;

    @JsonAlias({"externalServices", "external_services"})
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, ExternalService> externalServices = new LinkedHashMap<>();

    // The app's own actor identity for OBO credential retrieval: SHA-256 hex of its DIAL key, or its workload
    // client_id (azp). The caller's derived identity must equal it. Absent ⇒ OBO off.
    @JsonAlias({"appIdentity", "app_identity"})
    private String appIdentity;

    // Governance: when true, regular users (not just admins/owners) may author external services on this
    // app. Admin-set, default false ⇒ today's admin-only authoring is preserved.
    @JsonAlias({"allowUserExternalServices", "allow_user_external_services"})
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean allowUserExternalServices;

    // maintain the order of routes defined in the app config
    private LinkedHashMap<String, Route> routes = new LinkedHashMap<>();

    @JsonIgnore
    public Boolean hasApplicationTypeSchemaId() {
        return applicationTypeSchemaId != null;
    }

    /**
     * Indicates whether the application is invalid.
     * Only applicable for schema-rich applications.
     * Set to true when validation fails (e.g., missing required properties or schema violations).
     * Null when the application is valid.
     * This is a read-only property (not parsed from JSON).
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Boolean invalid = null;

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Function {

        private String id;
        private String runtime;
        private String authorBucket;
        private String sourceFolder;
        private String targetFolder;
        private Status status;
        private String error;
        private Mapping mapping;
        private Map<String, String> env;

        public enum Status {
            @JsonAlias("STARTING")
            DEPLOYING,
            @JsonAlias("STOPPING")
            UNDEPLOYING,
            @JsonAlias("STARTED")
            DEPLOYED,
            @JsonAlias({"CREATED", "STOPPED"})
            UNDEPLOYED,
            FAILED;

            public boolean isPending() {
                return switch (this) {
                    case DEPLOYED, FAILED, UNDEPLOYED -> false;
                    case DEPLOYING, UNDEPLOYING -> true;
                };
            }

            public boolean isActive() {
                return switch (this) {
                    case FAILED, UNDEPLOYED -> false;
                    case DEPLOYING, DEPLOYED, UNDEPLOYING -> true;
                };
            }
        }

        @Data
        @Accessors(chain = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
        public static class Mapping {
            @JsonAlias("completion")
            private String chatCompletion;
            private String rate;
            private String tokenize;
            private String truncatePrompt;
            private String configuration;
        }
    }

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Logs {
        private Collection<Log> logs;
    }

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Log {
        private String instance;
        private String content;
    }

    @Data
    public static class Mcp {
        @JsonAlias({"endpoint", "dial:endpoint"})
        private String endpoint;
        @JsonAlias({"transport", "dial:transport"})
        private final ToolSet.Transport transport = ToolSet.Transport.HTTP;
        @JsonAlias({"allowedTools", "allowed_tools", "dial:allowedTools"})
        private List<String> allowedTools = List.of();
        @JsonAlias({"configDelivery", "config_delivery", "dial:mcpConfigDelivery"})
        private McpConfigDelivery configDelivery = McpConfigDelivery.META;
        @JsonAlias({"forwardAuthToken", "forward_auth_token", "dial:forwardPerRequestKey"})
        private boolean forwardPerRequestKey = true;
    }

    public enum McpConfigDelivery {
        HEADER, META;
    }

    public Application() {
        super();
    }

    public Application(Application source) {
        super();
        this.setInvalid(source.getInvalid());
        this.setName(source.getName());
        this.setEndpoint(source.getEndpoint());
        this.setInterfaces(source.getInterfaces());
        this.setDisplayName(source.getDisplayName());
        this.setDisplayVersion(source.getDisplayVersion());
        this.setIconUrl(source.getIconUrl());
        this.setDescription(source.getDescription());
        this.setReference(source.getReference());
        this.setUserRoles(source.getUserRoles());
        this.setForwardAuthToken(source.isForwardAuthToken());
        this.setFeatures(source.getFeatures());
        this.setInputAttachmentTypes(source.getInputAttachmentTypes());
        this.setMaxInputAttachments(source.getMaxInputAttachments());
        this.setDefaults(source.getDefaults());
        this.setResponsesDefaults(source.getResponsesDefaults());
        this.setInterceptors(source.getInterceptors());
        this.setDescriptionKeywords(source.getDescriptionKeywords());
        this.setFunction(source.getFunction());
        this.setApplicationProperties(source.getApplicationProperties());
        this.setApplicationTypeSchemaId(source.getApplicationTypeSchemaId());
        this.setAuthor(source.getAuthor());
        this.setCreatedAt(source.getCreatedAt());
        this.setUpdatedAt(source.getUpdatedAt());
        this.setRoutes(source.getRoutes());
        this.setMcp(source.getMcp());
        this.setExternalServices(source.getExternalServices());
        this.setAppIdentity(source.getAppIdentity());
        this.setAllowUserExternalServices(source.isAllowUserExternalServices());
    }
}
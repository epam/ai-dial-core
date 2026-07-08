package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public abstract class Deployment extends RoleBasedEntity {
    private String endpoint;
    private String responsesEndpoint;
    /**
     * Supported LLM API interfaces keyed by interface-type value. Peer of endpoint/responsesEndpoint.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, DeploymentInterface> interfaces = Map.of();
    @JsonAlias({"displayName", "display_name"})
    private String displayName;
    @JsonAlias({"displayVersion", "display_version"})
    private String displayVersion;
    @JsonAlias({"iconUrl", "icon_url"})
    private String iconUrl;
    private String description;
    private String intro;
    private String reference;
    /**
     * Forward Http header with authorization token when request is sent to deployment.
     * Authorization token is NOT forwarded by default.
     */
    @JsonAlias({"forwardAuthToken", "forward_auth_token"})
    private boolean forwardAuthToken = false;
    private Features features;
    @JsonAlias({"inputAttachmentTypes", "input_attachment_types"})
    private List<String> inputAttachmentTypes;
    @JsonAlias({"maxInputAttachments", "max_input_attachments"})
    private Integer maxInputAttachments;
    /**
     * Default parameters are applied if a request doesn't contain them in OpenAI chat/completions API call.
     */
    private Map<String, Object> defaults = Map.of();
    /**
     * Default parameters are applied if a request doesn't contain them in OpenAI Responses API call.
     */
    private Map<String, Object> responsesDefaults = Map.of();
    /**
     * List of interceptors to be called for the deployment
     */
    private List<String> interceptors = List.of();
    /**
     * The field contains a list of keywords aka tags which describe the deployment, e.g. code-gen, text2image.
     */
    @JsonAlias({"descriptionKeywords", "description_keywords"})
    private List<String> descriptionKeywords = List.of();

    /**
     * Indicated max retry attempts to route a single user request.
     */
    @JsonAlias({"maxRetryAttempts", "max_retry_attempts"})
    private int maxRetryAttempts = 1;

    /**
     * The author who has developed that deployment(application/model)
     */
    private String author;

    @JsonAlias({"createdAt", "created_at"})
    private Long createdAt;

    @JsonAlias({"updatedAt", "updated_at"})
    private Long updatedAt;

    /**
     * Dependent deployments
     */
    private List<String> dependencies = List.of();

    /**
     * New-flow base URL for the type (trailing slash stripped), or null when not declared.
     */
    public String getInterfaceBaseUrl(InterfaceType type) {
        DeploymentInterface deploymentInterface = interfaces == null ? null : interfaces.get(type.getValue());
        if (deploymentInterface == null) {
            return null;
        }
        String url = deploymentInterface.getBaseUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Legacy fully-qualified endpoint configured for the type, or null.
     */
    public String getLegacyEndpoint(InterfaceType type) {
        if (type == InterfaceType.OPENAI_CHAT_COMPLETIONS) {
            return endpoint;
        }
        if (type == InterfaceType.OPENAI_RESPONSES) {
            return responsesEndpoint;
        }
        return null;
    }

    /**
     * Routing identifier for the type: interfaces base_url when declared (new flow), else the legacy
     * endpoint (verbatim flow). Used as the synthetic upstream id when a deployment has no upstreams.
     */
    public String resolveEndpoint(InterfaceType type) {
        String baseUrl = getInterfaceBaseUrl(type);
        return baseUrl != null ? baseUrl : getLegacyEndpoint(type);
    }

    /**
     * True when the deployment can serve the type via the new interfaces map OR a legacy endpoint.
     * This restores the legacy {@code getEndpoint() != null} semantics while also honouring interfaces.
     */
    public boolean supportsInterface(InterfaceType type) {
        return getInterfaceBaseUrl(type) != null || getLegacyEndpoint(type) != null;
    }
}

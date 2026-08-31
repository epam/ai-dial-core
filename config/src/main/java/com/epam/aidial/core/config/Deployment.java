package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Data
@EqualsAndHashCode(callSuper = true)
public abstract class Deployment extends RoleBasedEntity {

    private String endpoint;
    private String responsesEndpoint;
    /**
     * Root url shared by every {@link #interfaces} entry declaring no {@code base_url} of its own — in the
     * common case a single root serves all of them and only the ingress path differs. It stands for no
     * interface by itself: {@code interfaces} alone says which ones the deployment serves.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonAlias({"baseUrl", "base_url"})
    private String baseUrl;
    /**
     * Supported LLM API interfaces keyed by interface-type value. Peer of endpoint/responsesEndpoint.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, DeploymentInterface> interfaces = Map.of();
    /**
     * If set, the deployment is called under this name instead of its own: the outgoing request body's
     * {@code model} field (and the {@code X-DIAL-OVERRIDE-NAME} header) are rewritten to this value before
     * the request reaches the deployment's endpoint/adapter. Routing is unaffected — only the value the
     * endpoint receives changes.
     */
    @JsonAlias({"overrideName", "override_name"})
    private String overrideName;
    @JsonAlias({"displayName", "display_name"})
    private LocalizedValue displayName;
    @JsonAlias({"displayVersion", "display_version"})
    private String displayVersion;
    @JsonAlias({"iconUrl", "icon_url"})
    private String iconUrl;
    private LocalizedValue description;
    /**
     * Short introductory/onboarding text for the deployment, shown to the end user
     * separately from the more general-purpose {@link #description}.
     */
    private LocalizedValue intro;
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
     * Headers added to a request that carries none under that name, for every interface the deployment
     * serves. Overlaid per interface by {@code interfaces.<type>.defaultHeaders}, see
     * {@link #resolveDefaultHeaders}.
     */
    @JsonAlias({"defaultHeaders", "default_headers"})
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> defaultHeaders = Map.of();
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
     * Points to the registered catalog schema governing this deployment's
     * {@link #catalogProperties catalog metadata}.
     */
    @JsonAlias({"catalogSchemaId", "catalog_schema_id"})
    private URI catalogSchemaId;

    /**
     * Curated marketplace/catalog display metadata, validated against {@link #catalogSchemaId}.
     */
    @JsonAlias({"catalogProperties", "catalog_properties"})
    private Map<String, Object> catalogProperties;

    @JsonIgnore
    public boolean hasCatalogSchemaId() {
        return catalogSchemaId != null;
    }

    /**
     * The name under which this deployment is called: {@link #overrideName} when set, otherwise its own name.
     */
    @JsonIgnore
    public String getTargetName() {
        return overrideName != null ? overrideName : getName();
    }

    /**
     * The default headers in force for the interface type: the deployment-level {@link #defaultHeaders}
     * with {@code interfaces.<type>.defaultHeaders} laid over them. Names are compared case-insensitively,
     * so an interface entry overrides a deployment-level header however either spells it.
     */
    public Map<String, String> resolveDefaultHeaders(InterfaceType type) {
        DeploymentInterface declared = interfaces == null ? null : interfaces.get(type.getValue());
        Map<String, String> interfaceHeaders = declared == null ? Map.of() : declared.getDefaultHeaders();
        if (interfaceHeaders.isEmpty()) {
            return defaultHeaders;
        }
        // TreeMap for its case-insensitive comparator, not for ordering: an interface entry has to override a deployment-level header spelled in another case
        Map<String, String> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        merged.putAll(defaultHeaders);
        merged.putAll(interfaceHeaders);
        return merged;
    }
}

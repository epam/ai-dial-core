package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

@Data
@EqualsAndHashCode(callSuper = true)
public abstract class Deployment extends RoleBasedEntity {

    private static final Pattern DEPLOYMENT_SEGMENT =
            Pattern.compile("/deployments/(.+?)/(completions|chat/completions|embeddings)(?=$|\\?)");

    private String endpoint;
    private String responsesEndpoint;
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
     * New-flow base URL for the type (trailing slash stripped), or null when not declared.
     */
    @Nullable
    public String getInterfaceBaseUrl(InterfaceType type) {
        DeploymentInterface deploymentInterface = interfaces == null ? null : interfaces.get(type.getValue());
        if (deploymentInterface == null) {
            return null;
        }
        String url = deploymentInterface.getBaseUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * The endpoint the deployment declares for the type: the {@code interfaces} base URL when present,
     * else the type's legacy peer field — {@link #endpoint} for chat completions, {@link #responsesEndpoint}
     * for responses ({@code openaiEmbeddings} postdates the legacy fields and has no peer).
     */
    @Nullable
    private String declaredEndpoint(InterfaceType type) {
        String baseUrl = getInterfaceBaseUrl(type);
        if (baseUrl != null) {
            return baseUrl;
        }
        if (type == InterfaceType.OPENAI_CHAT_COMPLETIONS) {
            return endpoint;
        }
        if (type == InterfaceType.OPENAI_RESPONSES) {
            return responsesEndpoint;
        }
        return null;
    }

    /**
     * The interface whose declared configuration serves a request for the type. Identity, with one
     * exception: embeddings requests are served by the chat-completions configuration unless the
     * deployment declares {@code openaiEmbeddings} — the two were a single interface before the split,
     * so deployments configured back then keep routing {@code /embeddings} unchanged.
     */
    private InterfaceType servedBy(InterfaceType type) {
        return type == InterfaceType.OPENAI_EMBEDDINGS && !supportsInterface(type)
                ? InterfaceType.OPENAI_CHAT_COMPLETIONS
                : type;
    }

    /**
     * The endpoint a request for the type is routed to, or null when the deployment has no configuration
     * to serve it (callers answer 503). Also used as the synthetic upstream id when a deployment declares
     * no upstreams.
     */
    @Nullable
    public String resolveEndpoint(InterfaceType type) {
        return declaredEndpoint(servedBy(type));
    }

    /**
     * True when the deployment declares the type itself — what the listing APIs advertise. A deployment
     * serving embeddings only through the chat-completions fallback reports {@code false} for
     * {@code openaiEmbeddings} while {@link #resolveEndpoint} still routes it.
     */
    public boolean supportsInterface(InterfaceType type) {
        return declaredEndpoint(type) != null;
    }

    /**
     * Dual-mode: the absolute URI a request for the type is forwarded to. When the deployment declares an
     * {@code interfaces} base URL, that base URL plus the exact ingress path (with the {@code /deployments/{id}/}
     * segment rewritten to this deployment's own name, or to {@link #overrideName} when set); otherwise the
     * verbatim legacy endpoint plus the original query, byte-identical to the legacy flow.
     *
     * @param ingressUri the inbound request URI, already including path and query
     * @param query      the inbound query string, or null when absent
     */
    public String resolveRequestUri(InterfaceType type, String ingressUri, String query) {
        InterfaceType served = servedBy(type);
        String baseUrl = getInterfaceBaseUrl(served);
        if (baseUrl == null) {
            return declaredEndpoint(served) + (query == null ? "" : "?" + query);
        }
        return baseUrl + rewriteDeploymentPathSegment(ingressUri, getTargetName());
    }

    /**
     * The name under which this deployment is called: {@link #overrideName} when set, otherwise its own name.
     */
    @JsonIgnore
    public String getTargetName() {
        return overrideName != null ? overrideName : getName();
    }

    /**
     * Rewrites the {@code /deployments/{id}/} ingress path segment to this deployment's own name (or
     * {@link #overrideName} when set), whatever id it currently carries — including a multi-segment
     * canonical id (e.g. {@code models/platform/{name}} for a platform-bucket entity). A request
     * forwarded through an interceptor carries the literal pseudo id {@code interceptor} in the path
     * (see {@code DeploymentPostController#handle}) rather than this deployment's own name, so it needs
     * rewriting back. No-op for interfaces whose ingress path carries no deployment segment
     * (openaiResponses and anthropicMessages resolve the deployment from the request body instead).
     */
    private static String rewriteDeploymentPathSegment(String path, String targetName) {
        Matcher matcher = DEPLOYMENT_SEGMENT.matcher(path);
        if (!matcher.find()) {
            return path;
        }
        String replacement = "/deployments/" + targetName + "/" + matcher.group(2);
        return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }
}

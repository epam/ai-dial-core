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
    private String getInterfaceBaseUrl(InterfaceType type) {
        DeploymentInterface deploymentInterface = interfaces == null ? null : interfaces.get(type.getValue());
        if (deploymentInterface == null) {
            return null;
        }
        String url = deploymentInterface.getBaseUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * The pre-{@code interfaces} field that serves the type: {@link #responsesEndpoint} for the Responses
     * API, and the untyped {@link #endpoint} for the whole deployments-POST family. That single field
     * predates the split into typed interfaces, so it serves {@code /chat/completions}, {@code /completions}
     * and {@code /embeddings} alike — which is how every embeddings model was configured before
     * {@code openaiEmbeddings} existed.
     */
    @Nullable
    private String legacyEndpoint(InterfaceType type) {
        return switch (type) {
            case OPENAI_CHAT_COMPLETIONS, OPENAI_EMBEDDINGS -> endpoint;
            case OPENAI_RESPONSES -> responsesEndpoint;
            case ANTHROPIC_MESSAGES -> null;
        };
    }

    /**
     * The endpoint a request for the type is routed to: the typed {@code interfaces} base URL when
     * declared, else the legacy field serving the type. Null when the deployment has no configuration for
     * the type at all, which callers answer with 503. Also used as the synthetic upstream id when a
     * deployment declares no upstreams.
     */
    @Nullable
    public String resolveEndpoint(InterfaceType type) {
        String baseUrl = getInterfaceBaseUrl(type);
        return baseUrl != null ? baseUrl : legacyEndpoint(type);
    }

    /**
     * True when the deployment <em>declares</em> the type — what the listing APIs advertise, as opposed to
     * what {@link #resolveEndpoint} routes. The two differ for {@code openaiEmbeddings} only: the untyped
     * {@link #endpoint} is how every pre-interfaces model is configured, chat and embedding alike, so
     * reading it as an embeddings declaration would advertise {@code openaiEmbeddings} on every chat model.
     * Such a deployment therefore advertises {@code openaiChatCompletions} only, while still being routed
     * for {@code /embeddings}. Declaring {@code openaiEmbeddings} in {@code interfaces} is what advertises it.
     */
    public boolean supportsInterface(InterfaceType type) {
        return getInterfaceBaseUrl(type) != null
                || (type != InterfaceType.OPENAI_EMBEDDINGS && legacyEndpoint(type) != null);
    }

    /**
     * The single dual-mode decision: base URL plus {@code path} in the new flow, the verbatim legacy
     * endpoint in the legacy flow (where the path is already baked into the endpoint and {@code path} is
     * ignored). Callers append their own suffix/query. Keeping this the only place that branches on the
     * flow lets callers compose URIs without ever seeing which flow the deployment is configured for.
     */
    public String resolveUri(InterfaceType type, String path) {
        String baseUrl = getInterfaceBaseUrl(type);
        return baseUrl != null ? baseUrl + path : legacyEndpoint(type);
    }

    /**
     * The absolute URI a deployments-POST request for the type is forwarded to: {@link #resolveUri} of the
     * ingress path with the {@code /deployments/{id}/} segment rewritten to this deployment's own name (or
     * {@link #overrideName} when set), plus the original query. In the legacy flow this is byte-identical
     * to the pre-interfaces behaviour: verbatim endpoint plus query.
     *
     * @param ingressPath the inbound request path, without the query
     * @param query       the inbound query string, or null when absent
     */
    public String resolveRequestUri(InterfaceType type, String ingressPath, String query) {
        String uri = resolveUri(type, rewriteDeploymentPathSegment(ingressPath, getTargetName()));
        return query == null ? uri : uri + "?" + query;
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

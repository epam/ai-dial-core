package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.storage.util.UrlUtil;
import lombok.experimental.UtilityClass;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Resolves where a request for an interface type is forwarded, and which interface types a deployment
 * advertises. A deployment carries two configuration shapes: the typed {@code interfaces} map, whose
 * {@code base_url} is a root the ingress path is appended to, and the pre-{@code interfaces} {@code endpoint}
 * and {@code responsesEndpoint} fields, which hold a complete url that already carries the route.
 */
@UtilityClass
public class DeploymentEndpointUtil {

    private static final Pattern DEPLOYMENT_SEGMENT =
            Pattern.compile("/deployments/(.+?)/(completions|chat/completions|embeddings)(?=$|\\?)");

    private static final String OPENAI_RESPONSES_BASE_PATH = "/openai/v1/responses";

    /**
     * The endpoint a request for the type is sent to, or null when the deployment has nothing serving it —
     * callers answer 503. Doubles as the synthetic upstream id when a deployment declares no upstreams.
     */
    @Nullable
    public String servingEndpoint(Deployment deployment, InterfaceType type) {
        String baseUrl = interfaceBaseUrl(deployment, type);
        return baseUrl != null ? baseUrl : legacyEndpoint(deployment, type);
    }

    /**
     * The interface types the listing APIs advertise for the deployment. A {@code interfaces} entry declares
     * its own type; a pre-{@code interfaces} field declares the type matching what the deployment says it is,
     * so {@code endpoint} on a {@code type: embedding} model declares embeddings and chat completions on
     * anything else. Advertising is narrower than serving: {@code endpoint} still serves the whole
     * deployments-POST family whatever it declares.
     */
    public boolean declaresInterface(Deployment deployment, InterfaceType type) {
        if (interfaceBaseUrl(deployment, type) != null) {
            return true;
        }
        return switch (type) {
            case OPENAI_CHAT_COMPLETIONS -> deployment.getEndpoint() != null && !isEmbeddingModel(deployment);
            case OPENAI_EMBEDDINGS -> deployment.getEndpoint() != null && isEmbeddingModel(deployment);
            case OPENAI_RESPONSES -> deployment.getResponsesEndpoint() != null;
            default -> false;
        };
    }

    /**
     * The absolute uri a deployments-POST request is forwarded to. Under {@code interfaces} that is the
     * ingress path appended to the base url, with the {@code /deployments/{id}/} segment rewritten to the
     * name the deployment is called by. A pre-{@code interfaces} endpoint is a complete url that already
     * carries the route, so the ingress path plays no part in it — only the query is carried over.
     *
     * @param ingressPath the inbound request path, without the query
     * @param query       the inbound query string, or null when absent
     */
    public String requestUri(Deployment deployment, InterfaceType type, String ingressPath, @Nullable String query) {
        String baseUrl = interfaceBaseUrl(deployment, type);
        String uri = baseUrl != null
                ? baseUrl + rewriteDeploymentSegment(ingressPath, targetPathSegment(deployment))
                : legacyEndpoint(deployment, type);
        return query == null ? uri : uri + "?" + query;
    }

    /**
     * The Responses API base uri for the deployment. Callers append their own suffix and query.
     */
    public String responsesBaseUri(Deployment deployment) {
        return uri(deployment, InterfaceType.OPENAI_RESPONSES, OPENAI_RESPONSES_BASE_PATH);
    }

    private String uri(Deployment deployment, InterfaceType type, String path) {
        String baseUrl = interfaceBaseUrl(deployment, type);
        // a pre-interfaces endpoint is a complete url that already carries the route, so nothing is appended
        return baseUrl != null ? baseUrl + path : legacyEndpoint(deployment, type);
    }

    /**
     * The {@code base_url} declared for the type, trailing slash stripped, or null when the type is not in
     * the {@code interfaces} map.
     */
    @Nullable
    private String interfaceBaseUrl(Deployment deployment, InterfaceType type) {
        Map<String, DeploymentInterface> interfaces = deployment.getInterfaces();
        DeploymentInterface deploymentInterface = interfaces == null ? null : interfaces.get(type.getValue());
        if (deploymentInterface == null) {
            return null;
        }
        String baseUrl = deploymentInterface.getBaseUrl();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * The pre-{@code interfaces} field serving the type. {@code endpoint} predates the split into typed
     * interfaces, so it serves the whole deployments-POST family, {@code /embeddings} included.
     */
    @Nullable
    private String legacyEndpoint(Deployment deployment, InterfaceType type) {
        return switch (type) {
            case OPENAI_CHAT_COMPLETIONS, OPENAI_EMBEDDINGS -> deployment.getEndpoint();
            case OPENAI_RESPONSES -> deployment.getResponsesEndpoint();
            default -> null;
        };
    }

    private boolean isEmbeddingModel(Deployment deployment) {
        return deployment instanceof Model model && model.getType() == ModelType.EMBEDDING;
    }

    /**
     * The {@code {id}} path segment the deployment is addressed by. A name is already in url form — a custom
     * application carries its encoded resource url as its name — while {@code overrideName} is plain
     * configuration text and has to be escaped to stay inside one segment.
     */
    private String targetPathSegment(Deployment deployment) {
        String overrideName = deployment.getOverrideName();
        return overrideName != null ? UrlUtil.encodePathSegment(overrideName) : deployment.getName();
    }

    /**
     * Rewrites the {@code /deployments/{id}/} ingress segment to {@code targetSegment}, whatever id it
     * carries — including a multi-segment canonical id such as {@code models/platform/{name}}. A request
     * forwarded through an interceptor carries the pseudo id {@code interceptor} instead of the deployment's
     * own name, so it needs rewriting back. No-op for ingress paths with no deployment segment, which is how
     * openaiResponses and anthropicMessages arrive — they name the deployment in the request body.
     */
    private String rewriteDeploymentSegment(String path, String targetSegment) {
        Matcher matcher = DEPLOYMENT_SEGMENT.matcher(path);
        if (!matcher.find()) {
            return path;
        }
        String replacement = "/deployments/" + targetSegment + "/" + matcher.group(2);
        return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }
}

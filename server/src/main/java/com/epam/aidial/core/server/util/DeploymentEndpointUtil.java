package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.InterfaceMode;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Translator;
import com.epam.aidial.core.config.TranslatorRef;
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
 *
 * <p>Within the map, the interface's own {@code base_url} wins over the deployment-level one, and the
 * pre-{@code interfaces} fields are read only for a type the map does not declare.
 */
@UtilityClass
public class DeploymentEndpointUtil {

    private static final Pattern DEPLOYMENT_SEGMENT =
            Pattern.compile("/deployments/(?<deployment>.+?)/(?<action>completions|chat/completions|embeddings)(?=$|\\?)");

    private static final String OPENAI_RESPONSES_BASE_PATH = "/openai/v1/responses";

    /**
     * The endpoint a request for the type is sent to, or null when the deployment has nothing serving it —
     * callers answer 503. Doubles as the synthetic upstream id when a deployment declares no upstreams.
     */
    @Nullable
    public String resolveServingEndpoint(Deployment deployment, InterfaceType type) {
        String baseUrl = resolveInterfaceBaseUrl(deployment, type);
        return baseUrl != null ? baseUrl : resolveLegacyEndpoint(deployment, type);
    }

    /**
     * The interface types the listing APIs advertise for the deployment. A {@code interfaces} entry declares
     * its own type; a pre-{@code interfaces} field declares the type matching what the deployment says it is,
     * so {@code endpoint} on a {@code type: embedding} model declares embeddings and chat completions on
     * anything else. Advertising is narrower than serving: {@code endpoint} still serves the whole
     * deployments-POST family whatever it declares.
     */
    public boolean isInterfaceDeclared(Deployment deployment, InterfaceType type) {
        if (resolveInterfaceBaseUrl(deployment, type) != null) {
            return true;
        }
        // a translated interface is served by its translator alone, so one with none linked serves nothing
        if (resolveMode(deployment, type) == InterfaceMode.TRANSLATOR) {
            return false;
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
    public String resolveRequestUri(Deployment deployment, InterfaceType type, String ingressPath, @Nullable String query) {
        String baseUrl = resolveInterfaceBaseUrl(deployment, type);
        String uri = baseUrl != null
                ? baseUrl + rewriteDeploymentName(ingressPath, resolveDeploymentName(deployment))
                : resolveLegacyEndpoint(deployment, type);
        return query == null ? uri : uri + "?" + query;
    }

    /**
     * The Responses API base uri for the deployment. Callers append their own suffix and query, which is why
     * this cannot go through {@link #resolveRequestUri} — that one drops the ingress path in the
     * pre-{@code interfaces} flow, and an item operation still has to hang {@code /{id}/cancel} off the
     * legacy {@code responsesEndpoint}.
     */
    public String resolveResponsesBaseUri(Deployment deployment) {
        String baseUrl = resolveInterfaceBaseUrl(deployment, InterfaceType.OPENAI_RESPONSES);
        // a pre-interfaces endpoint is a complete url that already carries the route, so nothing is appended
        return baseUrl != null
                ? baseUrl + OPENAI_RESPONSES_BASE_PATH
                : resolveLegacyEndpoint(deployment, InterfaceType.OPENAI_RESPONSES);
    }

    /**
     * How the deployment serves the type. Both an interface declaring no {@code mode} and a type served by
     * a pre-{@code interfaces} endpoint are {@link InterfaceMode#PASSTHROUGH}.
     */
    public InterfaceMode resolveMode(Deployment deployment, InterfaceType type) {
        DeploymentInterface deploymentInterface = findInterface(deployment, type);
        InterfaceMode mode = deploymentInterface == null ? null : deploymentInterface.getMode();
        return mode == null ? InterfaceMode.PASSTHROUGH : mode;
    }

    /**
     * The base url serving the type, trailing slash stripped, or null when the type is not in the
     * {@code interfaces} map or nothing declares a url for it. A deployment-level {@code baseUrl} on its
     * own serves nothing — an interface has to be declared to claim it.
     */
    @Nullable
    private String resolveInterfaceBaseUrl(Deployment deployment, InterfaceType type) {
        DeploymentInterface deploymentInterface = findInterface(deployment, type);
        if (deploymentInterface == null) {
            return null;
        }
        String baseUrl = resolveBaseUrl(deployment, deploymentInterface);
        if (baseUrl == null) {
            return null;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * The url the entry is served by. A translated interface is served by its translator and by nothing
     * else — {@code mode} decides this, so that what a request is routed to and what it is charged for can
     * never disagree — while a pass-through one takes its own {@code base_url}, or the deployment-level
     * {@code baseUrl} when it declares none.
     */
    @Nullable
    private String resolveBaseUrl(Deployment deployment, DeploymentInterface deploymentInterface) {
        if (deploymentInterface.getMode() == InterfaceMode.TRANSLATOR) {
            TranslatorRef translator = deploymentInterface.getTranslator();
            Translator definition = translator == null ? null : translator.getDefinition();
            return definition == null ? null : definition.getBaseUrl();
        }
        return deploymentInterface.getBaseUrl() != null ? deploymentInterface.getBaseUrl() : deployment.getBaseUrl();
    }

    /**
     * The {@code interfaces} entry for the type, or null when the map has none — an interface mapped to an
     * explicit {@code null} reads the same as an absent one.
     */
    @Nullable
    private DeploymentInterface findInterface(Deployment deployment, InterfaceType type) {
        Map<String, DeploymentInterface> interfaces = deployment.getInterfaces();
        return interfaces == null ? null : interfaces.get(type.getValue());
    }

    /**
     * The pre-{@code interfaces} field serving the type. {@code endpoint} predates the split into typed
     * interfaces, so it serves the whole deployments-POST family, {@code /embeddings} included.
     *
     * <p>A translated interface never falls back here: routing it to the deployment itself would send the
     * request pass-through while {@code mode} still exempted it from limits.
     */
    @Nullable
    private String resolveLegacyEndpoint(Deployment deployment, InterfaceType type) {
        if (resolveMode(deployment, type) == InterfaceMode.TRANSLATOR) {
            return null;
        }
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
     * The name the deployment is addressed by upstream, ready to sit in one {@code {id}} path segment: the
     * {@code overrideName} when set, the deployment's own name otherwise. A name is already in url form — a
     * custom application carries its encoded resource url as its name — while {@code overrideName} is plain
     * configuration text and has to be escaped to stay inside one segment.
     */
    private String resolveDeploymentName(Deployment deployment) {
        String overrideName = deployment.getOverrideName();
        return overrideName != null ? UrlUtil.encodePathSegment(overrideName) : deployment.getName();
    }

    /**
     * Rewrites the {@code /deployments/{id}/} ingress segment to {@code deploymentName}, whatever id it
     * carries — including a multi-segment canonical id such as {@code models/platform/{name}}. A request
     * forwarded through an interceptor carries the pseudo id {@code interceptor} instead of the deployment's
     * own name, so it needs rewriting back. No-op for ingress paths with no deployment segment, which is how
     * openaiResponses and anthropicMessages arrive — they name the deployment in the request body.
     */
    private String rewriteDeploymentName(String path, String deploymentName) {
        Matcher matcher = DEPLOYMENT_SEGMENT.matcher(path);
        if (!matcher.find()) {
            return path;
        }
        String replacement = "/deployments/" + deploymentName + "/" + matcher.group("action");
        return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }
}

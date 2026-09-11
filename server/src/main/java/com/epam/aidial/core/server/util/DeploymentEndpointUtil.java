package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.InterfaceMode;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.OverridePathKey;
import com.epam.aidial.core.config.Translator;
import com.epam.aidial.core.config.TranslatorRef;
import com.epam.aidial.core.storage.util.UrlUtil;
import lombok.experimental.UtilityClass;

import java.util.HashMap;
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
 *
 * <p>A translated interface is served by a {@code translators} entry the deployment names, so every method
 * answering a url takes the registry — {@code Config.getTranslators()} of the config the deployment came
 * from — and resolves the name on each call rather than reading a copy frozen into the deployment.
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
    public String resolveServingEndpoint(Deployment deployment, InterfaceType type, Map<String, Translator> translators) {
        String baseUrl = resolveInterfaceBaseUrl(deployment, type, translators);
        return baseUrl != null ? baseUrl : resolveLegacyEndpoint(deployment, type);
    }

    /**
     * The interface types the listing APIs advertise for the deployment. A {@code interfaces} entry declares
     * its own type; a pre-{@code interfaces} field declares the type matching what the deployment says it is,
     * so {@code endpoint} on a {@code type: embedding} model declares embeddings and chat completions on
     * anything else. Advertising is narrower than serving: {@code endpoint} still serves the whole
     * deployments-POST family whatever it declares.
     *
     * <p>Advertising reads no registry: a translated interface is declared by carrying a translator
     * reference, whether or not the name resolves right now. A reference nothing registers is a serving
     * failure — the request path answers 503 for it — not a listing one, exactly as an application whose
     * backend is missing still lists.
     */
    public boolean isInterfaceDeclared(Deployment deployment, InterfaceType type) {
        DeploymentInterface deploymentInterface = findInterface(deployment, type);
        if (deploymentInterface != null && deploymentInterface.getMode() == InterfaceMode.TRANSLATOR) {
            return deploymentInterface.getTranslator() != null;
        }
        if (deploymentInterface != null && passthroughBaseUrl(deployment, deploymentInterface) != null) {
            return true;
        }
        if (resolveLegacyEndpoint(deployment, type) == null) {
            return false;
        }
        return switch (type) {
            case OPENAI_CHAT_COMPLETIONS -> !isEmbeddingModel(deployment);
            case OPENAI_EMBEDDINGS -> isEmbeddingModel(deployment);
            case OPENAI_RESPONSES -> true;
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
    public String resolveRequestUri(Deployment deployment, InterfaceType type, Map<String, Translator> translators,
                                    String ingressPath, @Nullable String query) {
        String baseUrl = resolveInterfaceBaseUrl(deployment, type, translators);
        String uri;
        if (baseUrl == null) {
            uri = resolveLegacyEndpoint(deployment, type);
        } else {
            OverridePathKey pathKey = findRequestPathKey(type, ingressPath);
            String template = findOverridePath(deployment, type, pathKey);
            uri = template != null
                    ? baseUrl + leadingSlash(PathTemplateUtil.render(template,
                            templateVariables(deployment, pathKey.isIdAvailable() ? deployment.getName() : null)))
                    : baseUrl + rewriteDeploymentName(ingressPath, resolveDeploymentName(deployment));
        }
        return query == null ? uri : uri + "?" + query;
    }

    /**
     * The absolute uri a Responses API item operation (get/delete/cancel, and the background poll) is
     * forwarded to, or null when nothing serves the type. An {@code overridePaths} template for the
     * operation replaces the whole path under the base url; otherwise the id and the operation's suffix
     * are appended to {@link #resolveResponsesBaseUri}. {@code responseId} is the id of this hop: the
     * upstream response id toward the provider, the dial one toward an interceptor.
     */
    @Nullable
    public String resolveResponseItemUri(Deployment deployment, Map<String, Translator> translators,
                                         OverridePathKey pathKey, String responseId, @Nullable String query) {
        String baseUrl = resolveInterfaceBaseUrl(deployment, InterfaceType.OPENAI_RESPONSES, translators);
        String template = baseUrl == null ? null : findOverridePath(deployment, InterfaceType.OPENAI_RESPONSES, pathKey);
        String uri;
        if (template != null) {
            uri = baseUrl + leadingSlash(PathTemplateUtil.render(template, templateVariables(deployment, responseId)));
        } else {
            String responsesBaseUri = resolveResponsesBaseUri(deployment, translators);
            uri = responsesBaseUri == null ? null : responsesBaseUri + "/" + responseId + itemOperationSuffix(pathKey);
        }
        return uri == null || query == null ? uri : uri + "?" + query;
    }

    /**
     * The Responses API base uri for the deployment. Callers append their own suffix and query, which is why
     * this cannot go through {@link #resolveRequestUri} — that one drops the ingress path in the
     * pre-{@code interfaces} flow, and an item operation still has to hang {@code /{id}/cancel} off the
     * legacy {@code responsesEndpoint}.
     */
    public String resolveResponsesBaseUri(Deployment deployment, Map<String, Translator> translators) {
        String baseUrl = resolveInterfaceBaseUrl(deployment, InterfaceType.OPENAI_RESPONSES, translators);
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
    private String resolveInterfaceBaseUrl(Deployment deployment, InterfaceType type, Map<String, Translator> translators) {
        DeploymentInterface deploymentInterface = findInterface(deployment, type);
        if (deploymentInterface == null) {
            return null;
        }
        String baseUrl = resolveBaseUrl(deployment, deploymentInterface, translators);
        if (baseUrl == null) {
            return null;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * The url the entry is served by. A translated interface is served by its translator and by nothing
     * else — {@code mode} decides this, so that what a request is routed to and what it is charged for can
     * never disagree — while a pass-through one takes its own {@code base_url}, or the deployment-level
     * {@code baseUrl} when it declares none. The translator reference is resolved against the registry on
     * each call, never from a copy held by the deployment.
     */
    @Nullable
    private String resolveBaseUrl(Deployment deployment, DeploymentInterface deploymentInterface, Map<String, Translator> translators) {
        if (deploymentInterface.getMode() == InterfaceMode.TRANSLATOR) {
            TranslatorRef translator = deploymentInterface.getTranslator();
            Translator definition = translator == null ? null : translator.resolve(translators);
            return definition == null ? null : definition.getBaseUrl();
        }
        return passthroughBaseUrl(deployment, deploymentInterface);
    }

    @Nullable
    private String passthroughBaseUrl(Deployment deployment, DeploymentInterface deploymentInterface) {
        return deploymentInterface.getBaseUrl() != null ? deploymentInterface.getBaseUrl() : deployment.getBaseUrl();
    }

    private String itemOperationSuffix(OverridePathKey pathKey) {
        return switch (pathKey) {
            case GET_OPENAI_RESPONSES_BY_ID, DELETE_OPENAI_RESPONSES_BY_ID -> "";
            case POST_OPENAI_RESPONSES_CANCEL -> "/cancel";
            default -> throw new IllegalArgumentException("Not a response item operation: " + pathKey);
        };
    }

    /**
     * The override key the ingress request maps to, or null for the one deployments-POST action with no
     * key of its own — the legacy {@code /completions}. The anthropic paths carry no deployment segment,
     * so the path itself picks between messages and count_tokens.
     */
    @Nullable
    private OverridePathKey findRequestPathKey(InterfaceType type, String ingressPath) {
        return switch (type) {
            case OPENAI_CHAT_COMPLETIONS -> isChatCompletionsPath(ingressPath)
                    ? OverridePathKey.POST_AZURE_OPENAI_CHAT_COMPLETIONS
                    : null;
            case OPENAI_EMBEDDINGS -> OverridePathKey.POST_AZURE_OPENAI_EMBEDDINGS;
            case OPENAI_RESPONSES -> OverridePathKey.POST_OPENAI_RESPONSES;
            case ANTHROPIC_MESSAGES -> ingressPath.endsWith("/count_tokens")
                    ? OverridePathKey.POST_ANTHROPIC_MESSAGES_COUNT_TOKENS
                    : OverridePathKey.POST_ANTHROPIC_MESSAGES;
        };
    }

    private boolean isChatCompletionsPath(String ingressPath) {
        Matcher matcher = DEPLOYMENT_SEGMENT.matcher(ingressPath);
        return matcher.find() && "chat/completions".equals(matcher.group("action"));
    }

    /**
     * The {@code overridePaths} template the interface entry declares for the operation, under the
     * camelCase key or its snake_case alias, or null when it declares neither. A translated interface
     * routes to its translator's DIAL-contract url, so overrides never apply to it.
     */
    @Nullable
    private String findOverridePath(Deployment deployment, InterfaceType type, @Nullable OverridePathKey pathKey) {
        if (pathKey == null) {
            return null;
        }
        DeploymentInterface deploymentInterface = findInterface(deployment, type);
        if (deploymentInterface == null || deploymentInterface.getMode() == InterfaceMode.TRANSLATOR) {
            return null;
        }
        Map<String, String> overridePaths = deploymentInterface.getOverridePaths();
        if (overridePaths == null) {
            return null;
        }
        String template = overridePaths.get(pathKey.getValue());
        return template != null ? template : overridePaths.get(pathKey.getAlias());
    }

    /**
     * The values an override template renders with: {@code overrideName} always — the name the deployment
     * is addressed by upstream, see {@link #resolveDeploymentName} — and {@code id} when the operation
     * carries one. For the deployments-POST family {@code id} is the deployment's own name rather than the
     * raw ingress segment: they are equal on a direct call, but the segment is the pseudo-id
     * {@code interceptor} on the callback hop, exactly the case {@link #rewriteDeploymentName} exists for.
     */
    private Map<String, String> templateVariables(Deployment deployment, @Nullable String id) {
        Map<String, String> variables = new HashMap<>();
        variables.put("overrideName", resolveDeploymentName(deployment));
        if (id != null) {
            variables.put("id", id);
        }
        return variables;
    }

    private String leadingSlash(String path) {
        return path.startsWith("/") ? path : "/" + path;
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
     * request pass-through while {@code mode} still exempted it from limits. That is the whole of the rule
     * that a translated interface is served by its translator or by nothing — one with no translator linked
     * resolves to no url here either, so it is neither served nor advertised.
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

package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ResourceDependency;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.alias.AliasPass;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.service.DeploymentGrants;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.vertx.core.MultiMap;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * The container keeps data associated with API key.
 *
 * <p>
 *     There are two types of API keys:
 *     <ul>
 *         <li>Project key is supplied from the Core config</li>
 *         <li>Per request key is generated in runtime and valid during the request</li>
 *     </ul>
 * </p>
 *
 * <p>
 *     Unknown fields are ignored, so that a key stored by another Core version during a rolling upgrade still decodes.
 * </p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiKeyData {
    private static final List<String> HTTP_HEADERS_TO_STORE = List.of(
            Proxy.HEADER_JOB_TITLE,
            Proxy.HEADER_CONVERSATION_ID
    );
    // per request key is available with during the request lifetime. It's generated in runtime
    private String perRequestKey;
    // the key of root request initiator
    private Key originalKey;
    // user claims extracted from JWT
    private ExtractedClaims extractedClaims;
    // OpenTelemetry trace ID
    private String traceId;
    // OpenTelemetry span ID created by the Core
    private String spanId;
    // list of attached file URLs collected from conversation history of the current request. Volatile: a grant from an
    // origin plugin's in-process call replaces these maps (copy on write) while its other calls read them on virtual threads
    private volatile Map<String, AutoSharedData> attachedFiles = new HashMap<>();
    private volatile Map<String, AutoSharedData> attachedFolders = new HashMap<>();
    // list of deployments included into application properties
    @JsonAlias({"attachedToolSets", "attachedDeployments"})
    private Map<String, AutoSharedData> attachedDeployments = new HashMap<>();
    private Map<String, AutoSharedData> attachedResourceCredentials = new HashMap<>();
    // list of prompts included into application properties
    private Map<String, AutoSharedData> attachedPrompts = new HashMap<>();
    // list of skills included into application properties
    private Map<String, AutoSharedData> attachedSkills = new HashMap<>();
    // deployment name of the source(application/model/interceptor) associated with the current request
    private String sourceDeployment;
    // Execution path of the root request
    private List<String> executionPath;
    // List of interceptors copied from the deployment config
    private List<String> interceptors;
    // Index to track which interceptor is called next
    private int interceptorIndex = -1;
    // a Responses or Messages alias pass made this key for its target pass. That pass then marks its no-budget 429, which by status
    // and body its alias pass cannot tell from another limit's 429; to other callers it stays unmarked. A key made from this one does
    // not copy it. Kept in memory only, for the target pass served in process: a Core without this field rejects a stored key with it
    @JsonIgnore
    private boolean madeByAliasPass;
    // deployment triggers interceptors
    private String initialDeployment;
    private String initialDeploymentApi;
    // Original HTTP headers to be stored during an interceptor invocation chain
    private Map<String, String> httpHeaders = new HashMap<>();
    // Map of receivers whom resources are shared to by per request API key.
    // A key is a deployment ID, value is a map of resource IDs and per request shared data
    private Map<String, Map<String, PerRequestSharedData>> perRequestReceivers = new HashMap<>();
    // Map of resources are shared by per request API key.
    // A key is a resource ID and value - per request shared data
    private Map<String, PerRequestSharedData> perRequestSharedResources = new HashMap<>();
    // the name of the alias whose call this key serves, recorded by the alias pass next to its grant of the target and
    // carried on with that grant; null otherwise
    private String aliasName;

    public ApiKeyData() {
    }

    public static ApiKeyData createProxyApiKeyData(ProxyContext context) {
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        context.setProxyApiKeyData(proxyApiKeyData);
        initFromContext(proxyApiKeyData, context);
        return proxyApiKeyData;
    }

    public static void initFromContext(ApiKeyData proxyApiKeyData, ProxyContext context) {
        ApiKeyData apiKeyData = context.getApiKeyData();
        List<String> currentPath;
        proxyApiKeyData.setHttpHeaders(collectHttpHeaders(context));

        if (apiKeyData.getPerRequestKey() == null) {
            proxyApiKeyData.setOriginalKey(context.getKey());
            proxyApiKeyData.setExtractedClaims(context.getExtractedClaims());
            proxyApiKeyData.setTraceId(context.getTraceId());
            currentPath = new ArrayList<>();
        } else {
            proxyApiKeyData.setOriginalKey(apiKeyData.getOriginalKey());
            proxyApiKeyData.setExtractedClaims(apiKeyData.getExtractedClaims());
            proxyApiKeyData.setTraceId(apiKeyData.getTraceId());
            currentPath = new ArrayList<>(context.getApiKeyData().getExecutionPath());
        }
        String receiver = null;
        if (context.getDecision() != null) {
            // the deployment the client called, so the router is the parent of the one it chose
            currentPath.add(context.getDecision().getRouterName());
        }
        if (context.getDeployment() != null) {
            receiver = context.getDeployment().getName();
            currentPath.add(receiver);
            proxyApiKeyData.setSourceDeployment(receiver);
        } else if (context.getRoute() != null) {
            receiver = context.getRoute().getName();
            currentPath.add(receiver);
            proxyApiKeyData.setSourceDeployment(receiver);
        }
        if (apiKeyData.getPerRequestKey() != null) {
            Map<String, Map<String, PerRequestSharedData>> receivers = apiKeyData.getPerRequestReceivers();
            if (receiver != null) {
                sharePerRequestPermissions(proxyApiKeyData, receivers, receiver);
            }
            // per request permissions are always shared to interceptors if the initial deployment has them
            if (context.getInitialDeployment() != null) {
                sharePerRequestPermissions(proxyApiKeyData, receivers, context.getInitialDeployment());
            }
        }
        // after the sharing above, whose putAll would replace a granted entry with a plain one of the same url
        if (context.getDeployment() instanceof AliasPass pass) {
            // the right to call the target, for the target pass, and the alias call it is granted by
            for (ResourceDependency dependency : pass.getTargetDependencies()) {
                proxyApiKeyData.grant(dependency.getTarget().getUrl(), dependency.getAccess());
            }
            proxyApiKeyData.setAliasName(pass.getAlias().getName());
        } else if (context.getDeployment() != null && context.getDecision() == null && apiKeyData.getPerRequestKey() != null) {
            // an interceptor in front of the target, and the target's own callback such as a translator's, call
            // Core back for the same target; the right to call it goes with them and covers that target only. A
            // granted router's choice is checked against the caller's own rights, so the right stops at the decision
            String url = DeploymentGrants.urlOf(context.getConfig(), servedDeployment(context, receiver));
            if (url != null && DeploymentGrants.isGranted(apiKeyData, url)) {
                proxyApiKeyData.grant(url, ResourceAccessType.READ_ONLY);
                proxyApiKeyData.setAliasName(apiKeyData.getAliasName());
            }
        }
        proxyApiKeyData.setExecutionPath(currentPath);
        proxyApiKeyData.setSpanId(context.getSpanId());
    }

    /**
     * Grants {@code access} on {@code url} in {@code perRequestSharedResources}, joined with what the key holds there.
     * Copy-on-write: the entry may be shared with the calling key's receivers.
     */
    public void grant(String url, Set<ResourceAccessType> access) {
        perRequestSharedResources.merge(url, new PerRequestSharedData(new HashSet<>(access)), (existing, added) -> {
            Set<ResourceAccessType> union = new HashSet<>(existing.permissions());
            union.addAll(added.permissions());
            return new PerRequestSharedData(union);
        });
    }

    /**
     * The deployment the pass acts for: for an interceptor's hop, the deployment its chain is in front of; for any other
     * pass, its own deployment {@code receiver}.
     */
    @Nullable
    private static String servedDeployment(ProxyContext context, @Nullable String receiver) {
        return context.getDeployment() instanceof Interceptor ? context.getInitialDeployment() : receiver;
    }

    private static void sharePerRequestPermissions(ApiKeyData proxyApiKeyData, Map<String, Map<String, PerRequestSharedData>> receivers,
                                                   String receiver) {
        if (receiver == null) {
            return;
        }
        Map<String, PerRequestSharedData> resources = receivers.get(receiver);
        if (resources != null) {
            proxyApiKeyData.getPerRequestSharedResources().putAll(resources);
        }
    }

    @JsonIgnore
    public boolean isInterceptor() {
        return perRequestKey != null && interceptors != null && interceptorIndex >= 0 && interceptorIndex < interceptors.size();
    }

    private static Map<String, String> collectHttpHeaders(ProxyContext context) {
        if (!context.getApiKeyData().getHttpHeaders().isEmpty()) {
            return context.getApiKeyData().getHttpHeaders();
        }
        Map<String, String> headers = new HashMap<>();
        MultiMap requestHeaders = context.getRequest().headers();
        for (String header : HTTP_HEADERS_TO_STORE) {
            if (requestHeaders.contains(header)) {
                headers.put(header, requestHeaders.get(header));
            }
        }
        return headers;
    }
}

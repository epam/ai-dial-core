package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.alias.AliasPass;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.storage.util.UrlUtil;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.vertx.core.MultiMap;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

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
 */
@Data
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
        // after the sharing above, whose putAll would otherwise replace a lent entry with a plain one for the same url
        if (context.getDeployment() != null) {
            lend(proxyApiKeyData, context, receiver);
        }
        proxyApiKeyData.setExecutionPath(currentPath);
        proxyApiKeyData.setSpanId(context.getSpanId());
    }

    private static void lend(ApiKeyData proxyApiKeyData, ProxyContext context, String receiver) {
        Map<String, PerRequestSharedData> grants = proxyApiKeyData.getPerRequestSharedResources();
        if (context.getDeployment() instanceof AliasPass pass) {
            // Grant the target pass the right to call the target: READ in perRequestSharedResources, lent=true so
            // that only this alias-authorized path bypasses accessibleByPerRequestKey and rate-limit role matching.
            grants.merge(pass.getTargetName(), new PerRequestSharedData(new HashSet<>(ResourceAccessType.READ_ONLY), true),
                    PerRequestSharedData::merge);
        } else {
            // An interceptor in front of the target, or the target's own callback (e.g. a translator's), calls
            // Core back for the same target: carry the lent grant forward so the target remains reachable.
            ApiKeyData apiKeyData = context.getApiKeyData();
            String lentKey = findLentGrantKey(apiKeyData, receiver, context.getInitialDeployment());
            if (lentKey != null) {
                grants.merge(lentKey, apiKeyData.getPerRequestSharedResources().get(lentKey), PerRequestSharedData::merge);
            }
        }
    }

    /**
     * The key of a lent grant in {@code apiKeyData.perRequestSharedResources} that covers {@code receiver} or
     * {@code initialDeployment} (by exact name or encoded path), or {@code null} when there is none. Only a PRK
     * carries lent grants; a project-key context returns {@code null}.
     */
    private static String findLentGrantKey(ApiKeyData apiKeyData, String receiver, String initialDeployment) {
        if (apiKeyData.getPerRequestKey() == null) {
            return null;
        }
        Map<String, PerRequestSharedData> grants = apiKeyData.getPerRequestSharedResources();
        if (hasLentGrant(grants, receiver)) {
            return receiver;
        }
        if (initialDeployment != null) {
            if (hasLentGrant(grants, initialDeployment)) {
                return initialDeployment;
            }
            String encoded = UrlUtil.encodePath(initialDeployment);
            if (hasLentGrant(grants, encoded)) {
                return encoded;
            }
        }
        return null;
    }

    public static boolean hasLentGrant(Map<String, PerRequestSharedData> grants, String key) {
        PerRequestSharedData data = key == null ? null : grants.get(key);
        return data != null && data.lent();
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

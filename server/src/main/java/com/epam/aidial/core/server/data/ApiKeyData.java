package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.vertx.core.MultiMap;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
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
// tolerate fields written by a newer core version, so per-request keys survive a rolling upgrade
@JsonIgnoreProperties(ignoreUnknown = true)
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
    // list of attached file URLs collected from conversation history of the current request
    private Map<String, AutoSharedData> attachedFiles = new HashMap<>();
    private Map<String, AutoSharedData> attachedFolders = new HashMap<>();
    // list of deployments included into application properties
    @JsonAlias({"attachedToolSets", "attachedDeployments"})
    private Map<String, AutoSharedData> attachedDeployments = new HashMap<>();
    private Map<String, AutoSharedData> attachedResourceCredentials = new HashMap<>();
    // list of prompts included into application properties
    private Map<String, AutoSharedData> attachedPrompts = new HashMap<>();
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
        proxyApiKeyData.setExecutionPath(currentPath);
        proxyApiKeyData.setSpanId(context.getSpanId());
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

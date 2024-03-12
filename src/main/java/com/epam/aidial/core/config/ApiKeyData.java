package com.epam.aidial.core.config;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.security.ExtractedClaims;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The container keeps data associated with API key.
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
    private Set<String> attachedFiles = new HashSet<>();
    private List<String> attachedFolders = new ArrayList<>();
    // deployment name of the source(application/assistant/model) associated with the current request
    private String sourceDeployment;
    // Execution path of the root request
    private List<String> executionPath;

    public ApiKeyData() {
    }

    public static void initFromContext(ApiKeyData proxyApiKeyData, ProxyContext context) {
        ApiKeyData apiKeyData = context.getApiKeyData();
        List<String> currentPath;
        if (apiKeyData.getPerRequestKey() == null) {
            proxyApiKeyData.setOriginalKey(context.getKey());
            proxyApiKeyData.setExtractedClaims(context.getExtractedClaims());
            proxyApiKeyData.setTraceId(context.getTraceId());
            currentPath = new ArrayList<>();
            currentPath.add(context.getProject() == null ? context.getUserHash() : context.getProject());
        } else {
            proxyApiKeyData.setOriginalKey(apiKeyData.getOriginalKey());
            proxyApiKeyData.setExtractedClaims(apiKeyData.getExtractedClaims());
            proxyApiKeyData.setTraceId(apiKeyData.getTraceId());
            currentPath = new ArrayList<>(context.getApiKeyData().getExecutionPath());
        }
        currentPath.add(context.getDeployment().getName());
        proxyApiKeyData.setExecutionPath(currentPath);
        proxyApiKeyData.setSpanId(context.getSpanId());
        proxyApiKeyData.setSourceDeployment(context.getDeployment().getName());
    }
}

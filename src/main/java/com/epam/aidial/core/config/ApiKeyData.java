package com.epam.aidial.core.config;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.security.ExtractedClaims;
import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Data
public class ApiKeyData {
    private String perRequestKey;
    private Key originalKey;
    private ExtractedClaims extractedClaims;
    private String traceId;
    private String spanId;
    private Set<String> attachedFiles = new HashSet<>();

    public ApiKeyData() {
    }

    public static void initFromContext(ApiKeyData proxyApiKeyData, ProxyContext context) {
        ApiKeyData apiKeyData = context.getApiKeyData();
        if (apiKeyData.getPerRequestKey() == null) {
            proxyApiKeyData.setOriginalKey(context.getKey());
            proxyApiKeyData.setExtractedClaims(context.getExtractedClaims());
            proxyApiKeyData.setTraceId(context.getTraceId());
        } else {
            proxyApiKeyData.setOriginalKey(apiKeyData.getOriginalKey());
            proxyApiKeyData.setExtractedClaims(apiKeyData.getExtractedClaims());
            proxyApiKeyData.setTraceId(apiKeyData.getTraceId());
        }
        proxyApiKeyData.setSpanId(context.getSpanId());
    }
}

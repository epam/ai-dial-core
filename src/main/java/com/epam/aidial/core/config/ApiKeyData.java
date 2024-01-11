package com.epam.aidial.core.config;

import com.epam.aidial.core.security.ExtractedClaims;
import lombok.Data;

@Data
public class ApiKeyData {
    private String perRequestKey;
    private Key originalKey;
    private ExtractedClaims extractedClaims;
    private String traceId;
    private String spanId;

    public ApiKeyData() {
    }

    public static ApiKeyData from(ApiKeyData another) {
        ApiKeyData data = new ApiKeyData();
        data.originalKey = another.originalKey;
        data.extractedClaims = another.extractedClaims;
        data.traceId = another.traceId;
        return data;
    }
}

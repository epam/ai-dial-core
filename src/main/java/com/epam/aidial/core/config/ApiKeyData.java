package com.epam.aidial.core.config;

import com.epam.aidial.core.security.ExtractedClaims;
import lombok.Data;

import java.util.HashSet;
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
    // deployment name of the source(application/assistant/model) associated with the current request
    private String sourceDeployment;

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

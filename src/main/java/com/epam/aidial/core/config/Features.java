package com.epam.aidial.core.config;

import lombok.Data;

@Data
public class Features {
    private String rateEndpoint;
    private String tokenizeEndpoint;
    private String truncatePromptEndpoint;

    /**
     * Whether the deployment supports a system prompt in the first message. Default is true.
     */
    private Boolean systemPromptSupported;

    /**
     * Whether the deployment supports a tools. Default is false.
     */
    private Boolean toolsSupported;

    /**
     * Whether the deployment supports a seed parameter. Default is false.
     */
    private Boolean seedSupported;
}
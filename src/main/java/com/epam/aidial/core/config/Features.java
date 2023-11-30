package com.epam.aidial.core.config;

import lombok.Data;

@Data
public class Features {
    private String rateEndpoint;
    private String tokenizeEndpoint;
    private String truncatePromptEndpoint;
}
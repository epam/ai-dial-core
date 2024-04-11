package com.epam.aidial.core.config;

import lombok.Data;

@Data
public class Features {
    private String rateEndpoint;
    private String tokenizeEndpoint;
    private String truncatePromptEndpoint;
    private String configurationEndpoint;

    private Boolean systemPromptSupported;
    private Boolean toolsSupported;
    private Boolean seedSupported;

    private Boolean urlAttachmentsSupported;
    private Boolean folderAttachmentsSupported;
}
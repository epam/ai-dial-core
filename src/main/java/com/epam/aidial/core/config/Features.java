package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
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
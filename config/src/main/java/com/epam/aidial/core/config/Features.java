package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Features {
    @JsonAlias({"rateEndpoint", "rate_endpoint"})
    private String rateEndpoint;
    @JsonAlias({"tokenizeEndpoint", "tokenize_endpoint"})
    private String tokenizeEndpoint;
    @JsonAlias({"truncatePromptEndpoint", "truncate_prompt_endpoint"})
    private String truncatePromptEndpoint;
    @JsonAlias({"configurationEndpoint", "configuration_endpoint"})
    private String configurationEndpoint;

    @JsonAlias({"systemPromptSupported", "system_prompt_supported"})
    private Boolean systemPromptSupported;
    @JsonAlias({"toolsSupported", "tools_supported"})
    private Boolean toolsSupported;
    @JsonAlias({"seedSupported", "seed_supported"})
    private Boolean seedSupported;

    @JsonAlias({"urlAttachmentsSupported", "url_attachments_supported"})
    private Boolean urlAttachmentsSupported;
    @JsonAlias({"folderAttachmentsSupported", "folder_attachments_supported"})
    private Boolean folderAttachmentsSupported;
    @JsonAlias({"allowResume", "allow_resume"})
    private Boolean allowResume;
    @JsonAlias({"accessibleByPerRequestKey", "accessible_by_per_request_key", "consentRequired"})
    private Boolean consentRequired;
    @JsonAlias({"contentPartsSupported", "content_parts_supported"})
    private Boolean contentPartsSupported;
    @JsonAlias({"temperatureSupported", "temperature_supported"})
    private Boolean temperatureSupported;
    @JsonAlias({"addonsSupported", "addons_supported"})
    private Boolean addonsSupported;
    @JsonAlias({"cacheSupported", "cache_supported"})
    private Boolean cacheSupported;
    /**
     * Try to create automatic cache, where it's possible. We will route request that is cached to same upstream.
     * But in case, when cache upstream is not available, we will fallback to another available upstream, and will create cache there.
     * <p>
     *  <b>Note</b>. Core should calculate hash for all prefixes for each request, and check their existence in Redis.
     * </p>
     */
    @JsonAlias({"autoCachingSupported", "auto_caching_supported"})
    private Boolean autoCachingSupported;
}
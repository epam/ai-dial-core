package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

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
    @JsonAlias({"accessibleByPerRequestKey", "accessible_by_per_request_key"})
    private Boolean accessibleByPerRequestKey;
    @JsonAlias({"contentPartsSupported", "content_parts_supported"})
    private Boolean contentPartsSupported;
    @JsonAlias({"temperatureSupported", "temperature_supported"})
    private Boolean temperatureSupported;
    @JsonAlias({"cacheSupported", "cache_supported"})
    private Boolean cacheSupported;
    /**
     * Try to create automatic cache, where it's possible. We will route request that is cached to same upstream.
     * But in case, when cache upstream is not available, we will fallback to another available upstream, and will create cache there.
     *
     * <p>
     *  <b>Note</b>. Core should calculate hash for all prefixes for each request, and check their existence in Redis.
     * </p>
     */
    @JsonAlias({"autoCachingSupported", "auto_caching_supported"})
    private Boolean autoCachingSupported;
    @JsonAlias({"consentRequired", "consent_required"})
    private Boolean consentRequired;

    @JsonAlias({"parallelToolCallsSupported", "parallel_tool_calls_supported"})
    private Boolean parallelToolCallsSupported;
    @JsonAlias({"assistantAttachmentsInRequestSupported", "assistant_attachments_in_request_supported"})
    private Boolean assistantAttachmentsInRequestSupported;
    @JsonAlias({"supportCommentInRateResponse", "support_comment_in_rate_response"})
    private Boolean supportCommentInRateResponse;

    // Feature flags that control which chat-completions parameters the upstream accepts.
    @JsonAlias({"maxTokensSupported", "max_tokens_supported"})
    private Boolean maxTokensSupported;

    @JsonAlias({"maxCompletionTokensSupported", "max_completion_tokens_supported"})
    private Boolean maxCompletionTokensSupported;

    @JsonAlias({"customTemperatureSupported", "custom_temperature_supported"})
    private Boolean customTemperatureSupported;

    @JsonAlias({"reasoningEfforts", "reasoning_efforts"})
    private List<String> reasoningEfforts;

    /**
     * Merges declared values before defaults are applied. Null means unspecified, including an explicit
     * JSON null; lists are replaced as a whole, including an explicitly empty list.
     */
    public static Features merge(Features base, Features overrides) {
        if (overrides == null) {
            return base;
        }
        if (base == null) {
            base = new Features();
        }
        Features merged = new Features();
        merged.rateEndpoint = override(base.rateEndpoint, overrides.rateEndpoint);
        merged.tokenizeEndpoint = override(base.tokenizeEndpoint, overrides.tokenizeEndpoint);
        merged.truncatePromptEndpoint = override(base.truncatePromptEndpoint, overrides.truncatePromptEndpoint);
        merged.configurationEndpoint = override(base.configurationEndpoint, overrides.configurationEndpoint);
        merged.systemPromptSupported = override(base.systemPromptSupported, overrides.systemPromptSupported);
        merged.toolsSupported = override(base.toolsSupported, overrides.toolsSupported);
        merged.seedSupported = override(base.seedSupported, overrides.seedSupported);
        merged.urlAttachmentsSupported = override(base.urlAttachmentsSupported, overrides.urlAttachmentsSupported);
        merged.folderAttachmentsSupported = override(base.folderAttachmentsSupported, overrides.folderAttachmentsSupported);
        merged.allowResume = override(base.allowResume, overrides.allowResume);
        merged.accessibleByPerRequestKey = override(base.accessibleByPerRequestKey, overrides.accessibleByPerRequestKey);
        merged.contentPartsSupported = override(base.contentPartsSupported, overrides.contentPartsSupported);
        merged.temperatureSupported = override(base.temperatureSupported, overrides.temperatureSupported);
        merged.cacheSupported = override(base.cacheSupported, overrides.cacheSupported);
        merged.autoCachingSupported = override(base.autoCachingSupported, overrides.autoCachingSupported);
        merged.consentRequired = override(base.consentRequired, overrides.consentRequired);
        merged.parallelToolCallsSupported = override(base.parallelToolCallsSupported, overrides.parallelToolCallsSupported);
        merged.assistantAttachmentsInRequestSupported = override(base.assistantAttachmentsInRequestSupported, overrides.assistantAttachmentsInRequestSupported);
        merged.supportCommentInRateResponse = override(base.supportCommentInRateResponse, overrides.supportCommentInRateResponse);
        merged.maxTokensSupported = override(base.maxTokensSupported, overrides.maxTokensSupported);
        merged.maxCompletionTokensSupported = override(base.maxCompletionTokensSupported, overrides.maxCompletionTokensSupported);
        merged.customTemperatureSupported = override(base.customTemperatureSupported, overrides.customTemperatureSupported);
        List<String> efforts = override(base.reasoningEfforts, overrides.reasoningEfforts);
        merged.reasoningEfforts = efforts == null ? null : List.copyOf(efforts);
        return merged;
    }

    private static <T> T override(T base, T value) {
        return value == null ? base : value;
    }
}

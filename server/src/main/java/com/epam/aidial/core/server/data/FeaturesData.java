package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.Features;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class FeaturesData {
    private boolean rate = false;
    private boolean tokenize = false;
    private boolean truncatePrompt = false;
    private boolean configuration = false;

    private boolean systemPrompt = true;
    private boolean tools = false;
    private boolean seed = false;
    private boolean urlAttachments = false;
    private boolean folderAttachments = false;
    private boolean allowResume = true;
    private boolean accessibleByPerRequestKey = true;
    private boolean contentParts = false;
    private boolean temperature = true;
    private boolean addons = true;
    private boolean cache = false;
    private boolean autoCaching = false;
    private boolean parallelToolCalls = true;

    @JsonIgnore
    public static FeaturesData createFeatures(Features features) {
        FeaturesData data = new FeaturesData();

        if (features == null) {
            return data;
        }

        data.setRate(features.getRateEndpoint() != null);
        data.setTokenize(features.getTokenizeEndpoint() != null);
        data.setTruncatePrompt(features.getTruncatePromptEndpoint() != null);
        data.setConfiguration(features.getConfigurationEndpoint() != null);

        if (features.getSystemPromptSupported() != null) {
            data.setSystemPrompt(features.getSystemPromptSupported());
        }

        if (features.getToolsSupported() != null) {
            data.setTools(features.getToolsSupported());
        }

        if (features.getSeedSupported() != null) {
            data.setSeed(features.getSeedSupported());
        }

        if (features.getUrlAttachmentsSupported() != null) {
            data.setUrlAttachments(features.getUrlAttachmentsSupported());
        }

        if (features.getFolderAttachmentsSupported() != null) {
            data.setFolderAttachments(features.getFolderAttachmentsSupported());
        }

        if (features.getAllowResume() != null) {
            data.setAllowResume(features.getAllowResume());
        }

        if (features.getConsentRequired() != null) {
            data.setAccessibleByPerRequestKey(features.getConsentRequired());
        }

        if (features.getContentPartsSupported() != null) {
            data.setContentParts(features.getContentPartsSupported());
        }

        if (features.getTemperatureSupported() != null) {
            data.setTemperature(features.getTemperatureSupported());
        }

        if (features.getAddonsSupported() != null) {
            data.setAddons(features.getAddonsSupported());
        }

        if (features.getCacheSupported() != null) {
            data.setCache(features.getCacheSupported());
        }

        if (features.getAutoCachingSupported() != null) {
            data.setAutoCaching(features.getAutoCachingSupported());
        }

        if (features.getParallelToolCallsSupported() != null) {
            data.setParallelToolCalls(features.getParallelToolCallsSupported());
        }

        return data;
    }
}
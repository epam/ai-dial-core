package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

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
    private boolean cache = false;
    private boolean autoCaching = false;
    private boolean parallelToolCalls = true;
    private boolean assistantAttachmentsInRequest = false;
    private boolean mcp = false;
    private boolean chatCompletion = false;
    private boolean responsesApi = false;
    private boolean maxTokensSupported = true;
    private boolean maxCompletionTokensSupported = false;
    private boolean customTemperatureSupported = true;
    private List<String> reasoningEfforts = List.of();

    /**
     * Features of a deployment: the configured {@link Features} plus the API-surface flags derived from the
     * deployment itself. A deployment serves an API when it declares it either in the {@code interfaces} map
     * or via a legacy endpoint, so both flavours must light up the corresponding feature flag.
     */
    @JsonIgnore
    public static FeaturesData createDeploymentFeatures(Deployment deployment) {
        FeaturesData data = createFeatures(deployment.getFeatures());
        data.setChatCompletion(DeploymentEndpointUtil.isInterfaceDeclared(deployment, InterfaceType.OPENAI_CHAT_COMPLETIONS));
        data.setResponsesApi(DeploymentEndpointUtil.isInterfaceDeclared(deployment, InterfaceType.OPENAI_RESPONSES));
        return data;
    }

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

        if (features.getCacheSupported() != null) {
            data.setCache(features.getCacheSupported());
        }

        if (features.getAutoCachingSupported() != null) {
            data.setAutoCaching(features.getAutoCachingSupported());
        }

        if (features.getParallelToolCallsSupported() != null) {
            data.setParallelToolCalls(features.getParallelToolCallsSupported());
        }

        if (features.getAssistantAttachmentsInRequestSupported() != null) {
            data.setAssistantAttachmentsInRequest(features.getAssistantAttachmentsInRequestSupported());
        }

        if (features.getMaxTokensSupported() != null) {
            data.setMaxTokensSupported(features.getMaxTokensSupported());
        }

        if (features.getMaxCompletionTokensSupported() != null) {
            data.setMaxCompletionTokensSupported(features.getMaxCompletionTokensSupported());
        }

        if (features.getCustomTemperatureSupported() != null) {
            data.setCustomTemperatureSupported(features.getCustomTemperatureSupported());
        }

        if (features.getReasoningEfforts() != null && !features.getReasoningEfforts().isEmpty()) {
            data.setReasoningEfforts(List.copyOf(features.getReasoningEfforts()));
        }

        return data;
    }
}
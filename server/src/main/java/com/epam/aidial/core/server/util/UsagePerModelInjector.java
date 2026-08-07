package com.epam.aidial.core.server.util;

import com.epam.aidial.core.server.token.UsagePerModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

import java.util.List;

@UtilityClass
public class UsagePerModelInjector {

    private static final String STATISTICS = "statistics";
    private static final String USAGE_PER_MODEL = "usage_per_model";

    /**
     * Sets (never merges) {@code statistics.usage_per_model} on {@code root}, so Core's value always
     * overrides whatever a deployment already put there.
     */
    public void inject(ObjectNode root, List<UsagePerModel> usagePerModel) {
        ArrayNode array = ProxyUtil.MAPPER.valueToTree(usagePerModel);
        JsonNode existing = root.get(STATISTICS);
        ObjectNode statistics = existing != null && existing.isObject() ? (ObjectNode) existing : root.putObject(STATISTICS);
        statistics.set(USAGE_PER_MODEL, array);
    }

    /**
     * Removes any {@code statistics.usage_per_model} a deployment already streamed, so it can never
     * positionally collide with Core's own array under {@link MergeChunks}' indexed-array merge (see
     * {@code AnalyticsLogContext#assembleStreamingChatCompletionsResponse}).
     */
    public void strip(ObjectNode root) {
        JsonNode statisticsNode = root.get(STATISTICS);
        if (statisticsNode == null || !statisticsNode.isObject()) {
            return;
        }
        ObjectNode statistics = (ObjectNode) statisticsNode;
        statistics.remove(USAGE_PER_MODEL);
        if (statistics.isEmpty()) {
            root.remove(STATISTICS);
        }
    }
}

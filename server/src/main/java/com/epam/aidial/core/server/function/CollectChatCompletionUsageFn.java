package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.MergeChunks;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;

/**
 * Accumulates the pricing-relevant fields of a streaming Chat Completions response - {@code usage}
 * (merged cumulatively, same as {@link com.epam.aidial.core.server.token.TokenUsageParser} assumes),
 * plus the {@code service_tier} and {@code custom_fields} siblings a plain token-usage byte-scan of
 * the buffered body would not otherwise carry forward for pricing decision-tree evaluation.
 */
public class CollectChatCompletionUsageFn extends BaseResponseFunction {

    private JsonNode usage;
    private JsonNode serviceTier;
    private JsonNode customFields;

    public CollectChatCompletionUsageFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Future<JsonNode> apply(JsonNode tree) {
        usage = MergeChunks.merge(usage, tree.get("usage"));
        if (tree.get("service_tier") != null) {
            serviceTier = tree.get("service_tier");
        }
        if (tree.get("custom_fields") != null) {
            customFields = tree.get("custom_fields");
        }

        ObjectNode merged = ProxyUtil.MAPPER.createObjectNode();
        if (usage != null) {
            merged.set("usage", usage);
        }
        if (serviceTier != null) {
            merged.set("service_tier", serviceTier);
        }
        if (customFields != null) {
            merged.set("custom_fields", customFields);
        }
        context.setPricingUsageNode(merged);

        return Future.succeededFuture(tree);
    }
}

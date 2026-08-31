package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.token.MessagesTokenUsageParser;
import com.epam.aidial.core.server.util.MergeChunks;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;

/**
 * Accumulates Anthropic streaming token usage, which is split across events: {@code message_start}
 * carries {@code input_tokens} (+ cache counters), while {@code message_delta} carries the final
 * cumulative {@code output_tokens} (newer API versions may repeat the other counters there too).
 * The generic {@link com.epam.aidial.core.server.token.TokenUsageParser} only sees the last
 * {@code usage} (output-only), losing the prompt count. This function observes each event (returning
 * the tree unchanged — pure pass-through) and stores the merged usage on the context, where
 * {@code MessagesController.parseTokenUsage} picks it up for rate-limit/stats.
 */
public class CollectMessagesTokenUsageFn extends BaseResponseFunction {

    private long inputTokens;
    private long outputTokens;
    private long cacheReadTokens;
    private long cacheCreationTokens;
    private long thinkingTokens;
    // Merged verbatim, unlike the scalars above, so pricing-relevant fields the scalars don't
    // capture (service_tier, the cache_creation TTL-bucket breakdown) survive for cost evaluation.
    private JsonNode mergedUsage;

    public CollectMessagesTokenUsageFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Future<JsonNode> apply(JsonNode tree) {
        JsonNode usage = switch (tree.path("type").asText()) {
            case "message_start" -> tree.path("message").path("usage");
            case "message_delta" -> tree.path("usage");
            default -> null;
        };
        if (usage != null && usage.isObject()) {
            // Counters are cumulative; fields absent from an event keep their previous values.
            inputTokens = usage.path("input_tokens").asLong(inputTokens);
            outputTokens = usage.path("output_tokens").asLong(outputTokens);
            cacheReadTokens = usage.path("cache_read_input_tokens").asLong(cacheReadTokens);
            cacheCreationTokens = usage.path("cache_creation_input_tokens").asLong(cacheCreationTokens);
            thinkingTokens = usage.path("output_tokens_details").path("thinking_tokens").asLong(thinkingTokens);
            context.setTokenUsage(MessagesTokenUsageParser.build(
                    inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens, thinkingTokens));
            mergedUsage = MergeChunks.merge(mergedUsage, usage);
            context.setPricingUsageNode(ProxyUtil.MAPPER.createObjectNode().set("usage", mergedUsage));
        }
        return Future.succeededFuture(tree);
    }
}

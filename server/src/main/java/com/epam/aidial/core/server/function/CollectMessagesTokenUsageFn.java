package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.token.MessagesTokenUsageParser;
import com.epam.aidial.core.server.util.MergeChunks;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;

/**
 * Accumulates Anthropic streaming token usage, which is split across events: {@code message_start}
 * carries {@code input_tokens} (+ cache counters), while {@code message_delta} carries the final
 * cumulative {@code output_tokens} (newer API versions may repeat the other counters there too).
 * The generic {@link com.epam.aidial.core.server.token.TokenUsageParser} only sees the last
 * {@code usage} (output-only), losing the prompt count. This function observes each event (returning
 * the tree unchanged — pure pass-through) and stores the merged usage on the context, where
 * {@code MessagesController.parseTokenUsage} picks it up for rate-limit/stats.
 *
 * <p>It also keeps a running {@code id}/{@code model}/{@code stop_reason} view on
 * {@link ProxyContext#getAssembledStreamingResponseTree()}, from the same {@code message_start}/
 * {@code message_delta} events it already parses for usage, so {@code GenAiTraceAttributes} can read
 * it directly instead of re-scanning and re-parsing the whole buffered SSE stream a second time.</p>
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
    private final ObjectNode assembledResponse = ProxyUtil.MAPPER.createObjectNode();

    public CollectMessagesTokenUsageFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Future<JsonNode> apply(JsonNode tree) {
        String type = tree.path("type").asText();
        JsonNode usage = switch (type) {
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
        if ("message_start".equals(type)) {
            JsonNode message = tree.path("message");
            assembledResponse.set("id", message.get("id"));
            assembledResponse.set("model", message.get("model"));
            context.setAssembledStreamingResponseTree(assembledResponse);
        } else if ("message_delta".equals(type)) {
            assembledResponse.set("stop_reason", tree.path("delta").get("stop_reason"));
            context.setAssembledStreamingResponseTree(assembledResponse);
        }
        return Future.succeededFuture(tree);
    }
}

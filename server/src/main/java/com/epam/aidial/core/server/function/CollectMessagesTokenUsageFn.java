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
 * <p>{@code message_start} and {@code message_delta} also carry the response id/model and stop reason
 * respectively, one level away from the {@code usage} node this already reads - captured here too so
 * {@code GenAiTraceAttributes} can read the cached node instead of re-scanning the buffered SSE stream
 * for the same three fields.</p>
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
    private String responseId;
    private String responseModel;
    private String stopReason;

    public CollectMessagesTokenUsageFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Future<JsonNode> apply(JsonNode tree) {
        JsonNode usage = switch (tree.path("type").asText()) {
            case "message_start" -> {
                JsonNode message = tree.path("message");
                responseId = textOrNull(message.get("id"));
                responseModel = textOrNull(message.get("model"));
                yield message.path("usage");
            }
            case "message_delta" -> {
                stopReason = textOrNull(tree.path("delta").get("stop_reason"));
                yield tree.path("usage");
            }
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
            ObjectNode merged = ProxyUtil.MAPPER.createObjectNode().set("usage", mergedUsage);
            if (responseId != null) {
                merged.put("id", responseId);
            }
            if (responseModel != null) {
                merged.put("model", responseModel);
            }
            if (stopReason != null) {
                merged.put("stop_reason", stopReason);
            }
            context.setPricingUsageNode(merged);
        }
        return Future.succeededFuture(tree);
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }
}

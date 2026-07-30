package com.epam.aidial.core.server.token;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.buffer.Buffer;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * Token-usage accounting for the Anthropic Messages API. Anthropic reports usage as
 * {@code {input_tokens, output_tokens, cache_read_input_tokens, cache_creation_input_tokens}} with
 * no {@code total_tokens}, and — unlike OpenAI, whose {@code prompt_tokens} includes cached tokens —
 * {@code input_tokens} EXCLUDES the cache counters. DIAL accounting follows the OpenAI semantics
 * that {@link TokenUsage} consumers (rate limiter, usage logs) are built around, so prompt tokens
 * here are {@code input + cache_read + cache_creation}, with {@code cache_read_input_tokens} exposed
 * as the {@link PromptTokensDetails#getCachedTokens()} subset and {@code cache_creation_input_tokens}
 * as the {@link PromptTokensDetails#getCacheWriteTokens()} subset.
 */
@Slf4j
@UtilityClass
public class MessagesTokenUsageParser {

    /**
     * Parses usage from a non-streaming Messages response body (top-level {@code usage} object).
     */
    public TokenUsage parse(Buffer body) {
        try {
            JsonNode usage = ProxyUtil.MAPPER.readTree(body.getBytes()).get("usage");
            if (usage == null || !usage.isObject()) {
                return null;
            }
            return fromUsageNode(usage);
        } catch (Throwable e) {
            log.warn("Can't parse Anthropic token usage: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Builds a {@link TokenUsage} from a single Anthropic {@code usage} JSON node.
     */
    public TokenUsage fromUsageNode(JsonNode usage) {
        return build(
                usage.path("input_tokens").asLong(0),
                usage.path("output_tokens").asLong(0),
                usage.path("cache_read_input_tokens").asLong(0),
                usage.path("cache_creation_input_tokens").asLong(0));
    }

    /**
     * Builds a {@link TokenUsage} from raw Anthropic usage counters (see the accounting rule above).
     */
    public TokenUsage build(long inputTokens, long outputTokens, long cacheReadTokens, long cacheCreationTokens) {
        long promptTokens = inputTokens + cacheReadTokens + cacheCreationTokens;
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(promptTokens);
        tokenUsage.setCompletionTokens(outputTokens);
        tokenUsage.setTotalTokens(promptTokens + outputTokens);
        if (cacheReadTokens > 0 || cacheCreationTokens > 0) {
            PromptTokensDetails details = new PromptTokensDetails();
            details.setCachedTokens(cacheReadTokens);
            details.setCacheWriteTokens(cacheCreationTokens);
            tokenUsage.setPromptTokensDetails(details);
        }
        return tokenUsage;
    }
}

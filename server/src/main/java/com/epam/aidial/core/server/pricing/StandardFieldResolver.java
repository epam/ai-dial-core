package com.epam.aidial.core.server.pricing;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.StandardField;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.experimental.UtilityClass;

import java.util.Map;
import java.util.function.Function;

/**
 * Resolves a {@link StandardField} against the usage shape of a specific upstream {@link InterfaceType},
 * per the standard-field alias table in {@code cache_pricing_decision_tree.md}. Shared by both a pricing
 * decision-tree condition's field lookup and the reserved cache-counter resolution, so there is exactly
 * one implementation of "what does cachedReadTokens/cachedWriteTokens mean for this shape."
 */
@UtilityClass
class StandardFieldResolver {

    private static final Map<InterfaceType, Map<StandardField, Function<JsonNode, JsonNode>>> TABLE = Map.of(
            InterfaceType.OPENAI_CHAT_COMPLETIONS, Map.of(
                    StandardField.CACHED_READ_TOKENS, r -> r.path("usage").path("prompt_tokens_details").path("cached_tokens"),
                    StandardField.CACHED_WRITE_TOKENS, r -> r.path("usage").path("prompt_tokens_details").path("cache_write_tokens"),
                    StandardField.PROMPT_TOKENS, r -> r.path("usage").path("prompt_tokens"),
                    StandardField.SERVICE_TIER, r -> r.path("service_tier")),
            InterfaceType.OPENAI_RESPONSES, Map.of(
                    StandardField.CACHED_READ_TOKENS, r -> r.path("usage").path("input_tokens_details").path("cached_tokens"),
                    StandardField.CACHED_WRITE_TOKENS, r -> r.path("usage").path("input_tokens_details").path("cache_write_tokens"),
                    StandardField.PROMPT_TOKENS, r -> r.path("usage").path("input_tokens"),
                    StandardField.SERVICE_TIER, r -> r.path("service_tier")),
            InterfaceType.ANTHROPIC_MESSAGES, Map.of(
                    StandardField.CACHED_READ_TOKENS, r -> r.path("usage").path("cache_read_input_tokens"),
                    StandardField.CACHED_WRITE_TOKENS, r -> r.path("usage").path("cache_creation_input_tokens"),
                    StandardField.PROMPT_TOKENS, StandardFieldResolver::anthropicPromptTokens,
                    StandardField.SERVICE_TIER, r -> r.path("usage").path("service_tier"),
                    StandardField.TTL, StandardFieldResolver::anthropicTtl));

    static JsonNode resolve(InterfaceType type, StandardField field, JsonNode root) {
        if (type == null) {
            return MissingNode.getInstance();
        }
        return TABLE.getOrDefault(type, Map.of())
                .getOrDefault(field, r -> MissingNode.getInstance())
                .apply(root);
    }

    private static JsonNode anthropicPromptTokens(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode()) {
            return MissingNode.getInstance();
        }
        long total = usage.path("input_tokens").asLong(0)
                + usage.path("cache_read_input_tokens").asLong(0)
                + usage.path("cache_creation_input_tokens").asLong(0);
        return LongNode.valueOf(total);
    }

    private static JsonNode anthropicTtl(JsonNode root) {
        JsonNode creation = root.path("usage").path("cache_creation");
        if (creation.path("ephemeral_1h_input_tokens").asLong(0) > 0) {
            return TextNode.valueOf("1h");
        }
        if (creation.path("ephemeral_5m_input_tokens").asLong(0) > 0) {
            return TextNode.valueOf("5m");
        }
        return MissingNode.getInstance();
    }
}

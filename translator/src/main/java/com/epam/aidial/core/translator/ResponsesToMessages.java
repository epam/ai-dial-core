package com.epam.aidial.core.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

/**
 * The parts of a Responses → Messages answer both the streaming and the non-streaming translation use, so the
 * two modes cannot disagree on them. Ported from the Python translator's {@code from_responses.py} and
 * {@code common.anthropic_usage}.
 */
@UtilityClass
public class ResponsesToMessages {

    /**
     * Anthropic {@code usage} from Responses {@code usage}. OpenAI reports one input total with cache reads and
     * writes as a breakdown of it; Anthropic reports three disjoint counters whose sum is the total. Core bills
     * the Anthropic response by rebuilding the prompt as that sum, so both cache counts come out of the total.
     * Where a breakdown exceeds its total, the total wins: the read is kept and the write takes what is left.
     */
    public static ObjectNode convertUsage(JsonNode usage) {
        long prompt = usage.path("input_tokens").asLong(0);
        JsonNode details = usage.path("input_tokens_details");
        long cached = details.path("cached_tokens").asLong(0);
        long cacheWrite = details.hasNonNull("cache_write_tokens")
                ? details.path("cache_write_tokens").asLong(0)
                : details.path("cacheWriteTokens").asLong(0);
        long output = usage.path("output_tokens").asLong(0);
        long reasoning = usage.path("output_tokens_details").path("reasoning_tokens").asLong(0);

        long cacheRead = Math.min(cached, prompt);
        long cacheCreation = Math.min(cacheWrite, prompt - cacheRead);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("input_tokens", prompt - cacheRead - cacheCreation);
        result.put("output_tokens", output);
        result.put("cache_read_input_tokens", cacheRead);
        result.put("cache_creation_input_tokens", cacheCreation);
        long thinking = Math.min(reasoning, output);
        if (thinking > 0) {
            result.putObject("output_tokens_details").put("thinking_tokens", thinking);
        }
        return result;
    }

    /**
     * Why generation ended, most specific first. Truncation and a content filter outrank a tool call: a
     * function call in a cut-off response is the one being written when the cap hit.
     */
    public static String stopReason(JsonNode response, boolean sawFunctionCall, boolean sawRefusal) {
        String reason = response.path("incomplete_details").path("reason").asText("");
        if ("incomplete".equals(response.path("status").asText()) && "max_output_tokens".equals(reason)) {
            return "max_tokens";
        }
        if ("content_filter".equals(reason)) {
            return "refusal";
        }
        if (sawFunctionCall) {
            return "tool_use";
        }
        return sawRefusal ? "refusal" : "end_turn";
    }

    /**
     * The Anthropic error type for an error an upstream reported inside a 200. {@code type} is read before
     * {@code code}: DIAL pairs a category type with a specific code. A failure nothing names is an
     * {@code api_error}, since nothing says the request was at fault.
     */
    public static String errorType(JsonNode error) {
        for (String field : new String[] {"type", "code"}) {
            String type = switch (error.path(field).asText("")) {
                case "invalid_request_error" -> "invalid_request_error";
                case "rate_limit_exceeded" -> "rate_limit_error";
                case "vector_store_timeout" -> "timeout_error";
                default -> null;
            };
            if (type != null) {
                return type;
            }
        }
        return "api_error";
    }
}

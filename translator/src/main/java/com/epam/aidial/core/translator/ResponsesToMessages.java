package com.epam.aidial.core.translator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * A non-streaming Responses → Messages answer, and the parts of it the streaming translation shares, so the two
 * modes cannot disagree on them. Ported from the Python translator's {@code from_responses.py}, {@code errors.py}
 * and {@code common.anthropic_usage}.
 */
@Slf4j
@UtilityClass
public class ResponsesToMessages {

    /**
     * The Responses {@code ResponseError.code} values that blame the request (its prompt, its images, a policy it
     * tripped): resending it unchanged fails the same way. {@code errors._REQUEST_FAULT_CODES}.
     */
    private static final Set<String> REQUEST_FAULT_CODES = Set.of("invalid_prompt", "data_residency_mismatch", "bio_policy",
            "misalignment_policy_violation", "invalid_image", "invalid_image_format", "invalid_base64_image", "invalid_image_url",
            "image_too_large", "image_too_small", "image_parse_error", "image_content_policy_violation", "invalid_image_mode",
            "image_file_too_large", "unsupported_image_media_type", "empty_image_file", "failed_to_download_image",
            "image_file_not_found");

    private static final Set<String> UNFINISHED_STATUSES = Set.of("queued", "in_progress", "cancelled");

    /**
     * Rejects text after the value, as Python's {@code json.loads} does.
     */
    private static final ObjectReader STRICT_JSON = new ObjectMapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

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
     * The Anthropic error for an error object an upstream reported inside a 200: a failed Responses object's
     * {@code error}, or one written into the stream. {@code type} is read before {@code code}: DIAL pairs a category
     * type with a specific code. A failure nothing names is a 502 {@code api_error}, since nothing says the request
     * was at fault. Ported from {@code errors.anthropic_error_from_openai_error}.
     */
    public static UpstreamFailureException failure(JsonNode error) {
        JsonNode text = error.path("message");
        String message = text.isTextual() ? text.textValue() : "The upstream request failed";
        for (String field : new String[] {"type", "code"}) {
            JsonNode name = error.path(field);
            String value = name.isTextual() ? name.textValue() : "";
            if ("invalid_request_error".equals(value) || REQUEST_FAULT_CODES.contains(value)) {
                return new UpstreamFailureException(400, "invalid_request_error", message);
            }
            if ("rate_limit_exceeded".equals(value)) {
                return new UpstreamFailureException(429, "rate_limit_error", message);
            }
            if ("vector_store_timeout".equals(value)) {
                return new UpstreamFailureException(504, "timeout_error", message);
            }
        }
        return new UpstreamFailureException(502, "api_error", message);
    }

    /**
     * One Anthropic message from a non-streaming Responses object, as {@code from_responses.py:from_responses}
     * builds it, with the fields its SDK model writes as null. Web search output is out of scope and dropped.
     *
     * @param messageId the id the client gets, in place of the upstream's own response id
     * @param toolNames the aliases the request's translation issued, restored in the tool calls
     * @param omitThinking the request's {@code thinking.display} is {@code omitted}
     * @throws UpstreamFailureException for a {@code failed} object, or one that never finished; any other status, a
     *                                  missing one included, is translated
     */
    public static ObjectNode translateResponse(JsonNode response, String requestedModel, String messageId, ToolNames toolNames,
                                               boolean omitThinking) {
        String status = response.path("status").asText("");
        if ("failed".equals(status)) {
            throw failure(response.path("error"));
        }
        if (UNFINISHED_STATUSES.contains(status)) {
            // background statuses this translator never asks for: end_turn would pass an unfinished answer off as done
            throw new UpstreamFailureException(502, "api_error", "The upstream response did not complete (status '" + status + "')");
        }

        ArrayNode content = JsonNodeFactory.instance.arrayNode();
        boolean sawFunctionCall = false;
        boolean sawRefusal = false;
        for (JsonNode item : response.path("output")) {
            switch (item.path("type").asText()) {
                case "reasoning" -> addReasoning(item, omitThinking, content);
                case "message" -> {
                    for (JsonNode part : item.path("content")) {
                        String partType = part.path("type").asText();
                        if ("output_text".equals(partType)) {
                            content.add(textBlock(part.path("text").asText("")));
                        } else if ("refusal".equals(partType)) {
                            sawRefusal = true;
                            content.add(textBlock(part.path("refusal").asText("")));
                        }
                    }
                }
                case "function_call" -> {
                    sawFunctionCall = true;
                    ObjectNode block = content.addObject();
                    block.put("type", "tool_use");
                    block.set("id", item.get("call_id"));
                    block.set("name", toolNames.restore(item.get("name")));
                    block.set("input", parseArguments(item.path("arguments")));
                    block.putNull("caller");
                }
                default -> log.debug("Dropping Responses output item of type: {}", item.path("type").asText());
            }
        }
        if (content.isEmpty()) {
            // never a zero-block message: the SDKs index content[0]
            content.add(textBlock(""));
        }

        ObjectNode message = JsonNodeFactory.instance.objectNode();
        message.put("id", messageId);
        message.put("type", "message");
        message.put("role", "assistant");
        String model = response.path("model").asText("");
        message.put("model", model.isEmpty() ? requestedModel : model);
        message.set("content", content);
        message.put("stop_reason", stopReason(response, sawFunctionCall, sawRefusal));
        message.putNull("stop_sequence");
        message.putNull("container");
        message.putNull("stop_details");
        ObjectNode usage = convertUsage(response.path("usage"));
        usage.putNull("cache_creation");
        usage.putNull("inference_geo");
        usage.putNull("server_tool_use");
        usage.putNull("service_tier");
        message.set("usage", usage);
        return message;
    }

    /**
     * An encrypted item becomes its carrier block; an unencrypted one a {@code thinking} block per text, unless the
     * thinking is omitted: blank unsigned thinking would only persist an unusable block in the client's history.
     */
    private static void addReasoning(JsonNode item, boolean omitThinking, ArrayNode content) {
        if (ReasoningCarrier.hasEncryptedContent(item)) {
            content.add(ReasoningCarrier.wholeBlock(item, omitThinking));
            return;
        }
        if (omitThinking) {
            return;
        }
        for (String field : new String[] {"summary", "content"}) {
            for (JsonNode part : item.path(field)) {
                String text = part.path("text").asText("");
                if (!text.isEmpty()) {
                    content.addObject().put("type", "thinking").put("thinking", text).put("signature", "");
                }
            }
        }
    }

    private static ObjectNode textBlock(String text) {
        ObjectNode block = JsonNodeFactory.instance.objectNode().put("type", "text").put("text", text);
        block.putNull("citations");
        return block;
    }

    /**
     * Arguments that are not a JSON object become {@code {}}: Anthropic's {@code input} must be an object, and a cut-off
     * call already says so through {@code stop_reason: max_tokens}.
     */
    private static ObjectNode parseArguments(JsonNode arguments) {
        if (arguments.isTextual() && !arguments.textValue().isEmpty()) {
            try {
                if (STRICT_JSON.readTree(arguments.textValue()) instanceof ObjectNode input) {
                    return input;
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse function_call arguments as JSON");
            }
        }
        return JsonNodeFactory.instance.objectNode();
    }
}

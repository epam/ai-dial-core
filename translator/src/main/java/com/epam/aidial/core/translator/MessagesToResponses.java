package com.epam.aidial.core.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic Messages request → OpenAI Responses request, ported from the Python translator's
 * {@code to_responses.py}, whose {@code CLAUDE.md} records why each rule is what it is.
 *
 * <p>Text only for now: in a user or assistant turn, a content block other than {@code text},
 * {@code compaction} and {@code mid_conv_system}, and any tool, is refused rather than dropped, because the
 * answer depends on it. A system turn keeps only its text, as Python does: it carries instructions, not content
 * the answer is about. A field with no Responses counterpart is dropped with a warning, because clients send
 * such fields on every request.
 */
@Slf4j
@UtilityClass
public class MessagesToResponses {

    /**
     * The Responses API enforces it on {@code user} although its schema states no cap. A longer value is
     * dropped rather than truncated: a shortened identifier names a different user.
     */
    private static final int MAX_USER_LENGTH = 64;

    /**
     * The smallest output budget the Responses API accepts, raised to so one-token probes still go through.
     */
    private static final int MIN_OUTPUT_TOKENS = 16;

    private static final List<String> DROPPED_FIELDS = List.of("top_k", "stop_sequences", "mcp_servers", "container",
            "inference_geo", "context_management", "compaction", "thinking", "output_config");

    /**
     * The Responses body for {@code request}, sent to the upstream as {@code model}. {@code stream} is left to
     * the caller, which is the transport.
     */
    public static ObjectNode translateRequest(JsonNode request, String model) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("model", model);
        result.set("input", translateMessages(request.path("messages")));

        String instructions = translateSystem(request.get("system"));
        if (instructions != null) {
            result.put("instructions", instructions);
        }
        if (request.hasNonNull("tools") || request.hasNonNull("tool_choice")) {
            throw new UntranslatableRequestException("Tools are not supported by the builtin translator yet");
        }
        for (String field : DROPPED_FIELDS) {
            if (request.hasNonNull(field)) {
                log.warn("Dropping '{}': not translated to the Responses API", field);
            }
        }

        // the Responses API stores every response unless told not to; the Messages API has nothing to store
        result.put("store", false);
        // asked for on every generation request, as the Python translator does, whether or not thinking is shown
        result.putArray("include").add("reasoning.encrypted_content");
        if (request.hasNonNull("max_tokens")) {
            int maxTokens = request.path("max_tokens").asInt();
            result.put("max_output_tokens", maxTokens > 0 && maxTokens < MIN_OUTPUT_TOKENS ? MIN_OUTPUT_TOKENS : maxTokens);
        }
        if (request.hasNonNull("temperature")) {
            result.set("temperature", request.get("temperature"));
        }
        if (request.hasNonNull("top_p")) {
            result.set("top_p", request.get("top_p"));
        }
        String serviceTier = switch (request.path("service_tier").asText("")) {
            case "auto" -> "auto";
            case "standard_only" -> "default";
            default -> null;
        };
        if (serviceTier != null) {
            result.put("service_tier", serviceTier);
        }
        String userId = request.path("metadata").path("user_id").asText("");
        if (!userId.isEmpty()) {
            if (userId.length() <= MAX_USER_LENGTH) {
                result.put("user", userId);
            } else {
                log.warn("Dropping 'metadata.user_id': {} characters, over the Responses API's {}-character 'user' limit",
                        userId.length(), MAX_USER_LENGTH);
            }
        }
        return result;
    }

    private static String translateSystem(JsonNode system) {
        if (system == null || system.isNull()) {
            return null;
        }
        if (system.isTextual()) {
            return system.asText().isEmpty() ? null : system.asText();
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode block : system) {
            if ("text".equals(block.path("type").asText())) {
                addText(texts, block.path("text").asText(""));
            } else {
                log.warn("Dropping unsupported system content block: {}", block.path("type").asText());
            }
        }
        return texts.isEmpty() ? null : String.join("\n\n", texts);
    }

    private static ArrayNode translateMessages(JsonNode messages) {
        if (!messages.isArray()) {
            throw new UntranslatableRequestException("'messages' must be an array");
        }
        ArrayNode input = JsonNodeFactory.instance.arrayNode();
        int lastUser = -1;
        for (int i = 0; i < messages.size(); i++) {
            if ("user".equals(messages.get(i).path("role").asText())) {
                lastUser = i;
            }
        }
        for (int i = 0; i < messages.size(); i++) {
            JsonNode message = messages.get(i);
            String role = message.path("role").asText();
            JsonNode content = message.path("content");
            switch (role) {
                case "user" -> addMessage(input, "user", inputTextParts(turnTexts(input, content, "user")));
                case "assistant" -> addAssistantMessage(input, turnTexts(input, content, "assistant"));
                case "system" -> {
                    // shown only for the user turn it follows: a later user turn expires it
                    if (!("next_user_message".equals(message.path("clear_at").asText()) && i < lastUser)) {
                        addMessage(input, "system", systemParts(content));
                    }
                }
                default -> throw new UntranslatableRequestException("Unknown message role: '" + role + "'");
            }
        }
        return input;
    }

    private static void addMessage(ArrayNode input, String role, ArrayNode parts) {
        if (!parts.isEmpty()) {
            ObjectNode message = input.addObject();
            message.put("type", "message");
            message.put("role", role);
            message.set("content", parts);
        }
    }

    /**
     * A replayed assistant turn goes as the lightweight input message with string content: the output-item
     * form would need the original item id and status.
     */
    private static void addAssistantMessage(ArrayNode input, List<String> texts) {
        if (!texts.isEmpty()) {
            ObjectNode message = input.addObject();
            message.put("type", "message");
            message.put("role", "assistant");
            message.put("content", String.join("\n", texts));
        }
    }

    private static ArrayNode inputTextParts(List<String> texts) {
        ArrayNode parts = JsonNodeFactory.instance.arrayNode();
        for (String text : texts) {
            parts.addObject().put("type", "input_text").put("text", text);
        }
        return parts;
    }

    /**
     * The non-empty texts of a user or assistant turn, a replayed {@code compaction} summary included. A
     * {@code mid_conv_system} block goes to {@code input} as a system message ahead of the turn: in front of the
     * user text it prefaces, and never between an assistant's function call and its output.
     */
    private static List<String> turnTexts(ArrayNode input, JsonNode content, String role) {
        List<String> texts = new ArrayList<>();
        if (content.isTextual()) {
            addText(texts, content.asText());
            return texts;
        }
        for (JsonNode block : content) {
            String type = block.path("type").asText();
            switch (type) {
                case "text" -> addText(texts, block.path("text").asText(""));
                // a null content is a failed compaction, which Anthropic treats as a no-op
                case "compaction" -> addText(texts, block.path("content").isTextual() ? block.path("content").asText() : "");
                case "mid_conv_system" -> addMessage(input, "system", systemParts(block.path("content")));
                default -> throw new UntranslatableRequestException("Content block '" + type + "' in the " + role
                        + " turn is not supported by the builtin translator yet");
            }
        }
        return texts;
    }

    /**
     * The parts of a system turn or a {@code mid_conv_system} block: its text, nested {@code mid_conv_system}
     * blocks included. Any other block is dropped.
     */
    private static ArrayNode systemParts(JsonNode content) {
        List<String> texts = new ArrayList<>();
        addSystemTexts(texts, content);
        return inputTextParts(texts);
    }

    private static void addSystemTexts(List<String> texts, JsonNode content) {
        if (content.isTextual()) {
            addText(texts, content.asText());
        } else if (content.isArray()) {
            for (JsonNode block : content) {
                switch (block.path("type").asText()) {
                    case "text" -> addText(texts, block.path("text").asText(""));
                    case "mid_conv_system" -> addSystemTexts(texts, block.path("content"));
                    default -> log.warn("Dropping unsupported system content block: {}", block.path("type").asText());
                }
            }
        }
    }

    private static void addText(List<String> texts, String text) {
        if (!text.isEmpty()) {
            texts.add(text);
        }
    }
}

package com.epam.aidial.core.translator;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.util.MinimalPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic Messages request → OpenAI Responses request, ported from the Python translator's
 * {@code to_responses.py}, whose {@code CLAUDE.md} records why each rule is what it is.
 *
 * <p>Text and tools for now: a content block with no translation yet (an image, a document, a thinking block,
 * or one inside a {@code tool_result} other than text), and a web search tool, are refused rather than dropped,
 * because the answer depends on them. A system turn keeps only its text, as Python does: it carries
 * instructions, not content the answer is about. A field with no Responses counterpart is dropped with a
 * warning, because clients send such fields on every request.
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

    // ponytail: a float is spelled the Java way (1.0E-5, Python 1e-05) and U+007F stays unescaped; the JSON value
    // is the same, only the string differs. Port Python's float repr if an upstream prompt cache ever misses on it.
    /**
     * Writes a replayed tool input the way Python's {@code json.dumps} does, so the upstream sees the prompt the
     * translator service sends for the same history.
     */
    private static final ObjectWriter PYTHON_JSON = new ObjectMapper()
            .writer(new MinimalPrettyPrinter() {
                @Override
                public void writeObjectFieldValueSeparator(JsonGenerator generator) throws IOException {
                    generator.writeRaw(": ");
                }

                @Override
                public void writeObjectEntrySeparator(JsonGenerator generator) throws IOException {
                    generator.writeRaw(", ");
                }

                @Override
                public void writeArrayValueSeparator(JsonGenerator generator) throws IOException {
                    generator.writeRaw(", ");
                }
            })
            .with(JsonWriteFeature.ESCAPE_NON_ASCII)
            .without(JsonWriteFeature.WRITE_HEX_UPPER_CASE);

    /**
     * The Responses body for {@code request}, sent to the upstream as {@code model}. {@code stream} is left to
     * the caller, which is the transport.
     *
     * @param toolNames receives the request's tool-name aliases, for the answer of the same request to restore
     */
    public static ObjectNode translateRequest(JsonNode request, String model, ToolNames toolNames) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("model", model);
        result.set("input", translateMessages(request.path("messages")));

        String instructions = translateSystem(request.get("system"));
        if (instructions != null) {
            result.put("instructions", instructions);
        }
        ArrayNode tools = translateTools(request.get("tools"));
        if (!tools.isEmpty()) {
            result.set("tools", tools);
        }
        translateToolChoice(request.get("tool_choice"), result);
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
        // last, so it also covers the function_call items replayed into input
        toolNames.applyResponses(result);
        return result;
    }

    /**
     * Every tool the client declared, never fewer: a custom tool becomes a function, and any other type is
     * forwarded as the client sent it, for the deployment to run or reject. Web search is refused: its answer
     * items have no translation back yet.
     */
    private static ArrayNode translateTools(JsonNode tools) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        if (tools == null || tools.isNull()) {
            return result;
        }
        if (!tools.isArray()) {
            throw new UntranslatableRequestException("'tools' must be an array");
        }
        for (int i = 0; i < tools.size(); i++) {
            JsonNode tool = tools.get(i);
            if (!tool.isObject()) {
                throw new UntranslatableRequestException("'tools[" + i + "]' must be an object");
            }
            String type = optionalString(tool, "type", "tools[" + i + "]");
            optionalString(tool, "name", "tools[" + i + "]");
            if (type == null || "custom".equals(type)) {
                result.add(functionTool(tool));
            } else if (type.startsWith("web_search")) {
                throw new UntranslatableRequestException("Tool type '" + type + "' is not supported by the builtin translator yet");
            } else {
                result.add(tool);
            }
        }
        return result;
    }

    private static String optionalString(JsonNode object, String field, String path) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new UntranslatableRequestException("'" + path + "." + field + "' must be a string");
        }
        return value.textValue();
    }

    /**
     * {@code strict} is sent as {@code false} unless the client set it: Anthropic tools are not strict unless
     * asked, and OpenAI's strict mode demands a schema subset ordinary tool schemas rarely meet.
     */
    private static ObjectNode functionTool(JsonNode tool) {
        ObjectNode function = JsonNodeFactory.instance.objectNode();
        function.put("type", "function");
        function.set("name", tool.get("name"));
        JsonNode description = tool.path("description");
        if (!description.asText("").isEmpty()) {
            function.set("description", description);
        }
        function.set("parameters", toolParameters(tool.get("input_schema")));
        function.put("strict", tool.path("strict").booleanValue());
        return function;
    }

    /**
     * The client's schema itself, or, when it names {@code $schema} (which OpenAI's {@code parameters} rejects),
     * a new object holding its other entries.
     */
    private static JsonNode toolParameters(JsonNode schema) {
        if (schema == null || schema.isNull()) {
            ObjectNode empty = JsonNodeFactory.instance.objectNode();
            empty.put("type", "object");
            empty.putObject("properties");
            return empty;
        }
        if (!schema.has("$schema")) {
            return schema;
        }
        ObjectNode parameters = JsonNodeFactory.instance.objectNode();
        parameters.setAll((ObjectNode) schema);
        parameters.remove("$schema");
        return parameters;
    }

    private static void translateToolChoice(JsonNode toolChoice, ObjectNode result) {
        if (toolChoice == null || toolChoice.isNull()) {
            return;
        }
        if (!toolChoice.isObject()) {
            throw new UntranslatableRequestException("'tool_choice' must be an object");
        }
        if (toolChoice.has("disable_parallel_tool_use")) {
            result.put("parallel_tool_calls", !toolChoice.path("disable_parallel_tool_use").asBoolean());
        }
        switch (toolChoice.path("type").asText("")) {
            case "auto" -> result.put("tool_choice", "auto");
            case "any" -> result.put("tool_choice", "required");
            case "none" -> result.put("tool_choice", "none");
            case "tool" -> result.putObject("tool_choice").put("type", "function").set("name", toolChoice.get("name"));
            default -> log.warn("Dropping 'tool_choice' of type '{}': not translated to the Responses API", toolChoice.path("type").asText());
        }
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
                case "user" -> addUserTurn(input, content);
                case "assistant" -> addAssistantTurn(input, content);
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
     * A user turn: its {@code tool_result} blocks first, as {@code function_call_output} items next to the calls
     * they answer, then its {@code mid_conv_system} blocks as system messages, then its texts, a replayed
     * {@code compaction} summary included, as one user message they preface.
     */
    private static void addUserTurn(ArrayNode input, JsonNode content) {
        List<String> texts = new ArrayList<>();
        if (content.isTextual()) {
            addText(texts, content.asText());
        } else {
            ArrayNode systemMessages = JsonNodeFactory.instance.arrayNode();
            for (JsonNode block : content) {
                String type = block.path("type").asText();
                switch (type) {
                    case "text" -> addText(texts, block.path("text").asText(""));
                    case "compaction" -> addText(texts, compactionText(block));
                    case "mid_conv_system" -> addMessage(systemMessages, "system", systemParts(block.path("content")));
                    case "tool_result" -> addFunctionCallOutput(input, block);
                    default -> throw unsupportedBlock(type, "user");
                }
            }
            input.addAll(systemMessages);
        }
        addMessage(input, "user", inputTextParts(texts));
    }

    /**
     * An assistant turn: each run of texts no {@code tool_use} interrupts as one assistant message, and each
     * {@code tool_use} as a {@code function_call} item at its place. A {@code mid_conv_system} block goes ahead of
     * the whole turn, never between a function call and its output.
     */
    private static void addAssistantTurn(ArrayNode input, JsonNode content) {
        List<String> texts = new ArrayList<>();
        if (content.isTextual()) {
            addText(texts, content.asText());
            addAssistantMessage(input, texts);
            return;
        }
        ArrayNode items = JsonNodeFactory.instance.arrayNode();
        for (JsonNode block : content) {
            String type = block.path("type").asText();
            switch (type) {
                case "text" -> addText(texts, block.path("text").asText(""));
                case "compaction" -> addText(texts, compactionText(block));
                case "mid_conv_system" -> addMessage(input, "system", systemParts(block.path("content")));
                case "tool_use" -> {
                    addAssistantMessage(items, texts);
                    texts.clear();
                    addFunctionCall(items, block);
                }
                default -> throw unsupportedBlock(type, "assistant");
            }
        }
        addAssistantMessage(items, texts);
        input.addAll(items);
    }

    /**
     * A null content is a failed compaction, which Anthropic treats as a no-op.
     */
    private static String compactionText(JsonNode block) {
        return block.path("content").isTextual() ? block.path("content").asText() : "";
    }

    private static UntranslatableRequestException unsupportedBlock(String type, String where) {
        return new UntranslatableRequestException("Content block '" + type + "' in the " + where
                + " turn is not supported by the builtin translator yet");
    }

    private static void addFunctionCall(ArrayNode items, JsonNode block) {
        ObjectNode call = items.addObject();
        call.put("type", "function_call");
        call.set("call_id", block.get("id"));
        call.set("name", block.get("name"));
        JsonNode arguments = block.path("input");
        call.put("arguments", writePythonJson(isPythonFalsy(arguments) ? JsonNodeFactory.instance.objectNode() : arguments));
    }

    /**
     * What Python's {@code block.get("input") or {}} replaces: absent, null, and every falsy value.
     */
    private static boolean isPythonFalsy(JsonNode value) {
        return value.isMissingNode() || value.isNull()
                || value.isContainerNode() && value.isEmpty()
                || value.isTextual() && value.textValue().isEmpty()
                || value.isNumber() && value.doubleValue() == 0
                || value.isBoolean() && !value.booleanValue();
    }

    @SneakyThrows
    private static String writePythonJson(JsonNode value) {
        return PYTHON_JSON.writeValueAsString(value);
    }

    /**
     * Text only: string content as it is, else the non-empty {@code text} blocks joined by a newline, with
     * {@code tool_reference} blocks (which name a tool for tool search to load) dropped. {@code is_error} has no
     * counterpart and becomes an {@code Error: } prefix.
     */
    private static void addFunctionCallOutput(ArrayNode input, JsonNode block) {
        JsonNode content = block.path("content");
        String output = "";
        if (content.isTextual()) {
            output = content.asText();
        } else if (content.isArray()) {
            List<String> texts = new ArrayList<>();
            for (JsonNode part : content) {
                String type = part.path("type").asText();
                switch (type) {
                    case "text" -> addText(texts, part.path("text").asText(""));
                    case "tool_reference" -> log.debug("Dropping tool_reference inside a tool_result");
                    default -> throw unsupportedBlock(type, "tool_result of the user");
                }
            }
            output = String.join("\n", texts);
        }
        ObjectNode item = input.addObject();
        item.put("type", "function_call_output");
        item.set("call_id", block.get("tool_use_id"));
        item.put("output", block.path("is_error").asBoolean() ? "Error: " + output : output);
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

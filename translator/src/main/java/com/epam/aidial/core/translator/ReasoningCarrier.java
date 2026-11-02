package com.epam.aidial.core.translator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * An encrypted Responses reasoning item carried through an Anthropic thinking block, in the Python translator's
 * format ({@code reasoning.py}), so a conversation moves between it and the builtin translator either way. The
 * carrier is the JSON text {@code {"dial_translator_reasoning_wrapper":{"version":2,"id":…,"encrypted_content":…}}}
 * in a {@code thinking} block's {@code signature} or a {@code redacted_thinking} block's {@code data}. Its values are
 * never decoded: on a model with configured upstreams they are the ones Core stamped for upstream affinity.
 */
@UtilityClass
class ReasoningCarrier {

    private static final String WRAPPER_KEY = "dial_translator_reasoning_wrapper";
    private static final int VERSION = 2;
    private static final Set<String> PAYLOAD_KEYS = Set.of("version", "id", "encrypted_content");

    /**
     * Strict as Python's {@code json.loads} with its duplicate-key hook: text after the object fails, where
     * Jackson otherwise ignores it.
     */
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * Writes as {@code json.dumps(..., ensure_ascii=False, separators=(",", ":"))} does, control characters
     * escaped with lowercase hex.
     */
    private static final ObjectWriter PYTHON_COMPACT_JSON = STRICT_JSON.writer().without(JsonWriteFeature.WRITE_HEX_UPPER_CASE);

    static boolean hasEncryptedContent(JsonNode item) {
        JsonNode value = item.path("encrypted_content");
        return value.isTextual() && !value.textValue().isEmpty();
    }

    /**
     * The carrier of {@code item}, which must have encrypted content.
     */
    @SneakyThrows
    static String encode(JsonNode item) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("version", VERSION);
        JsonNode id = item.path("id");
        if (id.isTextual() && !id.textValue().isEmpty()) {
            payload.set("id", id);
        }
        payload.set("encrypted_content", item.get("encrypted_content"));
        ObjectNode wrapper = JsonNodeFactory.instance.objectNode();
        wrapper.set(WRAPPER_KEY, payload);
        return PYTHON_COMPACT_JSON.writeValueAsString(wrapper);
    }

    /**
     * The {@code reasoning} input item a replayed {@code thinking} or {@code redacted_thinking} block stands for,
     * or null when the block holds no valid carrier (a Claude signature, another version, any extra key): the
     * Python translator drops such a block, and so does the caller.
     */
    static ObjectNode decode(JsonNode block) {
        boolean redacted = "redacted_thinking".equals(block.path("type").asText());
        JsonNode value = block.path(redacted ? "data" : "signature");
        ObjectNode payload = value.isTextual() ? parsePayload(value.textValue()) : null;
        if (payload == null) {
            return null;
        }
        ObjectNode item = JsonNodeFactory.instance.objectNode();
        item.put("type", "reasoning");
        ArrayNode summary = item.putArray("summary");
        JsonNode text = block.path("thinking");
        if (!redacted && text.isTextual() && !text.textValue().isEmpty()) {
            summary.addObject().put("type", "summary_text").set("text", text);
        }
        item.set("encrypted_content", payload.get("encrypted_content"));
        if (payload.has("id")) {
            item.set("id", payload.get("id"));
        }
        return item;
    }

    private static ObjectNode parsePayload(String value) {
        if (!value.stripLeading().startsWith("{")) {
            return null;
        }
        JsonNode wrapper;
        try {
            wrapper = STRICT_JSON.readTree(value);
        } catch (JsonProcessingException e) {
            return null;
        }
        if (wrapper.size() != 1 || !(wrapper.get(WRAPPER_KEY) instanceof ObjectNode payload)) {
            return null;
        }
        for (String key : (Iterable<String>) payload::fieldNames) {
            if (!PAYLOAD_KEYS.contains(key)) {
                return null;
            }
        }
        JsonNode version = payload.path("version");
        JsonNode id = payload.get("id");
        boolean valid = version.isInt() && version.intValue() == VERSION && hasEncryptedContent(payload)
                && (id == null || id.isTextual() && !id.textValue().isEmpty());
        return valid ? payload : null;
    }

    /**
     * The {@code redacted_thinking} block that carries {@code item}.
     */
    static ObjectNode redactedBlock(JsonNode item) {
        return JsonNodeFactory.instance.objectNode().put("type", "redacted_thinking").put("data", encode(item));
    }

    /**
     * {@code item} as one block with nothing streamed before it: {@code thinking} with its summary and content
     * texts, or {@code redacted_thinking} when it has none. {@code item} must have encrypted content.
     */
    static ObjectNode wholeBlock(JsonNode item, boolean omitThinking) {
        List<String> texts = new ArrayList<>();
        for (String field : new String[] {"summary", "content"}) {
            for (JsonNode part : item.path(field)) {
                JsonNode text = part.path("text");
                if (text.isTextual() && !text.textValue().isEmpty()) {
                    texts.add(text.textValue());
                }
            }
        }
        if (texts.isEmpty()) {
            return redactedBlock(item);
        }
        return JsonNodeFactory.instance.objectNode().put("type", "thinking")
                .put("thinking", omitThinking ? "" : String.join("\n\n", texts))
                .put("signature", encode(item));
    }
}

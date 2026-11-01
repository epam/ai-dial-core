package com.epam.aidial.core.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Responses SSE events → Anthropic Messages SSE events, for one response. Ported from the Python translator's
 * {@code ResponsesToAnthropicStream}.
 *
 * <p>Clients assert the exact Anthropic sequence {@code message_start → ping → (content_block_start → delta* →
 * content_block_stop)* → message_delta → message_stop}, so blocks are strictly sequential: opening one closes
 * the one that is open. The open block is held as a Responses coordinate key beside its Anthropic index, and a
 * delta for any other coordinate is never emitted with a guessed index. The stream ends in exactly one
 * {@code message_stop} or {@code error}; one that ends before a terminal Responses event is an error, not a
 * finished answer.
 *
 * <p>A reasoning item becomes a {@code thinking} block, its encrypted content the block's carrier
 * ({@link ReasoningCarrier}), sent before the block stops. A carrier that comes while another block is open is held
 * and sent as a block of its own at the next safe point, never inside that block and never twice.
 *
 * <p>Text, tool calls and reasoning for now. Not thread-safe: one instance per response, fed in event order.
 */
@Slf4j
public class ResponsesToMessagesStream {

    private final String requestedModel;
    private final String messageId;
    private final ToolNames toolNames;
    private final boolean omitThinking;
    private String model;
    private boolean started;
    private boolean terminated;
    private int nextIndex;
    private String openKey;
    private int openIndex;
    private boolean sawRefusal;
    private boolean sawFunctionCall;

    /**
     * Whether the open block's function call arguments have reached the client.
     */
    private boolean openArgumentsSent;

    /**
     * The summary or content part each reasoning key's last delta belonged to; a delta of another part starts
     * with a blank line.
     */
    private final Map<String, String> lastPartByReasoningKey = new HashMap<>();

    private final Set<String> reasoningCarried = new HashSet<>();

    /**
     * Encrypted reasoning items that completed while another block was open, by key, in arrival order.
     */
    private final Map<String, JsonNode> heldReasoningByKey = new LinkedHashMap<>();

    /**
     * @param messageId the id the client gets for the message, in place of the upstream's own response id
     * @param toolNames the aliases the request's translation issued, restored in the tool calls the client gets
     * @param omitThinking the request's {@code thinking.display} is {@code omitted}: reasoning text is not sent,
     *                     its carriers are
     */
    public ResponsesToMessagesStream(String requestedModel, String messageId, ToolNames toolNames, boolean omitThinking) {
        this.requestedModel = requestedModel;
        this.messageId = messageId;
        this.toolNames = toolNames;
        this.omitThinking = omitThinking;
        this.model = requestedModel;
    }

    /**
     * The Anthropic SSE frames, as text, that one Responses event translates to. Dispatched on the event's
     * {@code type} field rather than the SSE {@code event:} line.
     */
    public List<String> handle(JsonNode event) {
        List<String> frames = new ArrayList<>();
        if (terminated) {
            return frames;
        }
        String type = event.path("type").asText();
        switch (type) {
            case "response.created", "response.in_progress" -> onCreated(event, frames);
            case "response.content_part.added" -> {
                if ("output_text".equals(event.path("part").path("type").asText())) {
                    openBlock(textKey(event), textBlock(), frames);
                }
            }
            case "response.output_text.delta" -> onTextDelta(event, frames);
            case "response.refusal.delta" -> {
                sawRefusal = true;
                onTextDelta(event, frames);
            }
            case "response.content_part.done" -> {
                if (textKey(event).equals(openKey)) {
                    closeOpen(frames);
                }
            }
            case "response.output_item.added" -> onOutputItemAdded(event, frames);
            case "response.function_call_arguments.delta" -> onArgumentsDelta(event, frames);
            case "response.function_call_arguments.done" -> flushArguments(functionKey(event), event.path("arguments"), frames);
            case "response.output_item.done" -> onOutputItemDone(event, frames);
            case "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> onReasoningDelta(event, frames);
            case "response.completed", "response.incomplete" -> onFinal(event.path("response"), frames);
            case "response.failed" -> emitError(event.path("response").path("error"), frames);
            case "error" -> emitError(event.has("error") ? event.path("error") : event, frames);
            default -> log.debug("Ignoring Responses event: {}", type);
        }
        return frames;
    }

    /**
     * The frames to send when the upstream stream has ended. Empty when a terminal event closed the message;
     * otherwise the answer is truncated, and an {@code error} says so where a {@code message_stop} would pass
     * it off as complete.
     */
    public List<String> finish() {
        List<String> frames = new ArrayList<>();
        if (!terminated) {
            log.warn("Responses stream ended without a terminal event");
            error("api_error", "The upstream stream ended before the response completed", frames);
        }
        return frames;
    }

    private void onCreated(JsonNode event, List<String> frames) {
        if (started) {
            return;
        }
        model = event.path("response").path("model").asText(requestedModel);
        start(frames);
    }

    private void onTextDelta(JsonNode event, List<String> frames) {
        String key = textKey(event);
        if (!key.equals(openKey)) {
            // opened lazily: a refusal never sends content_part.added
            openBlock(key, textBlock(), frames);
        }
        ObjectNode delta = JsonNodeFactory.instance.objectNode();
        delta.put("type", "text_delta");
        delta.put("text", event.path("delta").asText(""));
        emitDelta(delta, frames);
    }

    private void onOutputItemDone(JsonNode event, List<String> frames) {
        JsonNode item = event.path("item");
        String type = item.path("type").asText();
        if ("reasoning".equals(type)) {
            finishReasoning(reasoningKey(event), item, frames);
        } else if ("function_call".equals(type)) {
            String key = functionKey(event);
            flushArguments(key, item.path("arguments"), frames);
            if (key.equals(openKey)) {
                closeOpen(frames);
            }
            flushHeldReasoning(frames);
        }
    }

    /**
     * Opened at the first non-empty delta, never on the part events: those are no evidence of text.
     */
    private void onReasoningDelta(JsonNode event, List<String> frames) {
        String text = event.path("delta").asText("");
        if (text.isEmpty() || omitThinking) {
            return;
        }
        String key = reasoningKey(event);
        if (!key.equals(openKey)) {
            openBlock(key, JsonNodeFactory.instance.objectNode().put("type", "thinking").put("thinking", "").put("signature", ""), frames);
        }
        String part = "response.reasoning_text.delta".equals(event.path("type").asText())
                ? "content:" + event.path("content_index").asInt(0)
                : "summary:" + event.path("summary_index").asInt(0);
        String previous = lastPartByReasoningKey.put(key, part);
        if (previous != null && !previous.equals(part)) {
            text = "\n\n" + text;
        }
        emitDelta(JsonNodeFactory.instance.objectNode().put("type", "thinking_delta").put("thinking", text), frames);
    }

    /**
     * The carrier of a completed reasoning item: into its open thinking block, else as a block of its own, held
     * while another block is open. The first copy with encrypted content wins; one held earlier outranks a later
     * terminal copy, which the upstream may have encrypted afresh.
     */
    private void finishReasoning(String key, JsonNode completed, List<String> frames) {
        if (reasoningCarried.contains(key)) {
            return;
        }
        JsonNode item = heldReasoningByKey.getOrDefault(key, completed);
        if (!ReasoningCarrier.hasEncryptedContent(item)) {
            if (key.equals(openKey)) {
                closeOpen(frames);
            }
            return;
        }
        if (openKey != null && !openKey.equals(key)) {
            heldReasoningByKey.putIfAbsent(key, item);
            return;
        }
        reasoningCarried.add(key);
        heldReasoningByKey.remove(key);
        if (key.equals(openKey)) {
            emitDelta(JsonNodeFactory.instance.objectNode().put("type", "signature_delta").put("signature", ReasoningCarrier.encode(item)), frames);
            closeOpen(frames);
        } else if (lastPartByReasoningKey.containsKey(key)) {
            // its thinking block was opened and its text sent, so the carrier goes out alone
            emitWhole(ReasoningCarrier.redactedBlock(item), frames);
        } else {
            emitWhole(ReasoningCarrier.wholeBlock(item, omitThinking), frames);
        }
    }

    private void flushHeldReasoning(List<String> frames) {
        if (openKey != null) {
            return;
        }
        for (Map.Entry<String, JsonNode> held : new ArrayList<>(heldReasoningByKey.entrySet())) {
            finishReasoning(held.getKey(), held.getValue(), frames);
        }
    }

    private void onOutputItemAdded(JsonNode event, List<String> frames) {
        JsonNode item = event.path("item");
        // a message or reasoning item opens on its part events
        if ("function_call".equals(item.path("type").asText())) {
            sawFunctionCall = true;
            ObjectNode block = JsonNodeFactory.instance.objectNode();
            block.put("type", "tool_use");
            block.set("id", item.get("call_id"));
            block.set("name", toolNames.restore(item.get("name")));
            block.putObject("input");
            openBlock(functionKey(event), block, frames);
        }
    }

    /**
     * Sent as it comes, an empty delta included; only a non-empty one counts as the arguments having been sent.
     */
    private void onArgumentsDelta(JsonNode event, List<String> frames) {
        String key = functionKey(event);
        if (!key.equals(openKey)) {
            return;
        }
        String delta = event.path("delta").asText("");
        emitDelta(inputJsonDelta(delta), frames);
        if (!delta.isEmpty()) {
            openArgumentsSent = true;
        }
    }

    /**
     * The whole arguments, once, for a call no delta carried them for: only the {@code *.done} events are certain
     * to hold them, and without this a call from an upstream that skips the deltas reaches the client with an
     * empty {@code input}.
     */
    private void flushArguments(String key, JsonNode arguments, List<String> frames) {
        String whole = arguments.asText("");
        if (key.equals(openKey) && !whole.isEmpty() && !openArgumentsSent) {
            emitDelta(inputJsonDelta(whole), frames);
            openArgumentsSent = true;
        }
    }

    private static ObjectNode inputJsonDelta(String partialJson) {
        return JsonNodeFactory.instance.objectNode().put("type", "input_json_delta").put("partial_json", partialJson);
    }

    private void emitDelta(ObjectNode delta, List<String> frames) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("type", "content_block_delta");
        data.put("index", openIndex);
        data.set("delta", delta);
        frames.add(frame("content_block_delta", data));
    }

    private void onFinal(JsonNode response, List<String> frames) {
        start(frames);
        // an upstream may skip output_item.done, so the terminal copies carry what it did not
        JsonNode output = response.path("output");
        for (int i = 0; output.isArray() && i < output.size(); i++) {
            JsonNode item = output.get(i);
            if ("reasoning".equals(item.path("type").asText()) && ReasoningCarrier.hasEncryptedContent(item)) {
                finishReasoning(reasoningKey(i), item, frames);
            }
        }
        closeOpen(frames);
        flushHeldReasoning(frames);
        if (nextIndex == 0) {
            // never a zero-block message: the SDKs index content[0]
            emitWhole(textBlock(), frames);
        }
        ObjectNode delta = JsonNodeFactory.instance.objectNode();
        delta.put("stop_reason", ResponsesToMessages.stopReason(response, sawFunctionCall, sawRefusal));
        delta.putNull("stop_sequence");
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("type", "message_delta");
        data.set("delta", delta);
        data.set("usage", ResponsesToMessages.convertUsage(response.path("usage")));
        frames.add(frame("message_delta", data));
        frames.add(frame("message_stop", JsonNodeFactory.instance.objectNode().put("type", "message_stop")));
        terminated = true;
    }

    private void emitError(JsonNode error, List<String> frames) {
        error(ResponsesToMessages.errorType(error), error.path("message").asText("The upstream request failed"), frames);
    }

    private void error(String type, String message, List<String> frames) {
        terminated = true;
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("type", "error");
        data.putObject("error").put("type", type).put("message", message);
        frames.add(frame("error", data));
    }

    /**
     * {@code message_start} and {@code ping}, once and before anything else: a block with no message to
     * attach to breaks every SDK parser.
     */
    private void start(List<String> frames) {
        if (started) {
            return;
        }
        started = true;
        ObjectNode message = JsonNodeFactory.instance.objectNode();
        message.put("id", messageId);
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", model);
        message.putArray("content");
        message.putNull("stop_reason");
        message.putNull("stop_sequence");
        message.putObject("usage")
                .put("input_tokens", 0)
                .put("output_tokens", 0)
                .put("cache_creation_input_tokens", 0)
                .put("cache_read_input_tokens", 0);
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("type", "message_start");
        data.set("message", message);
        frames.add(frame("message_start", data));
        frames.add(frame("ping", JsonNodeFactory.instance.objectNode().put("type", "ping")));
    }

    private void openBlock(String key, ObjectNode block, List<String> frames) {
        start(frames);
        closeOpen(frames);
        openKey = key;
        openArgumentsSent = false;
        openIndex = nextIndex++;
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("type", "content_block_start");
        data.put("index", openIndex);
        data.set("content_block", block);
        frames.add(frame("content_block_start", data));
    }

    /**
     * A block with no delta form, opened and closed at once.
     */
    private void emitWhole(ObjectNode block, List<String> frames) {
        openBlock("whole", block, frames);
        closeOpen(frames);
    }

    private void closeOpen(List<String> frames) {
        if (openKey == null) {
            return;
        }
        openKey = null;
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("type", "content_block_stop");
        data.put("index", openIndex);
        frames.add(frame("content_block_stop", data));
    }

    private static ObjectNode textBlock() {
        return JsonNodeFactory.instance.objectNode().put("type", "text").put("text", "");
    }

    private static String textKey(JsonNode event) {
        return "m:" + event.path("output_index").asInt(0) + ":" + event.path("content_index").asInt(0);
    }

    private static String functionKey(JsonNode event) {
        return "f:" + event.path("output_index").asInt(0);
    }

    private static String reasoningKey(JsonNode event) {
        return reasoningKey(event.path("output_index").asInt(0));
    }

    private static String reasoningKey(int outputIndex) {
        return "r:" + outputIndex;
    }

    /**
     * Both the {@code event:} line and the {@code type} field inside {@code data} are required: the
     * Anthropic SDKs read the latter.
     */
    private static String frame(String name, ObjectNode data) {
        return "event: " + name + "\ndata: " + data + "\n\n";
    }
}

package com.epam.aidial.core.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

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
 * <p>Text only for now. Not thread-safe: one instance per response, fed in event order.
 */
@Slf4j
public class ResponsesToMessagesStream {

    private final String requestedModel;
    private final String messageId;
    private String model;
    private boolean started;
    private boolean terminated;
    private int nextIndex;
    private String openKey;
    private int openIndex;
    private boolean sawRefusal;

    /**
     * @param messageId the id the client gets for the message, in place of the upstream's own response id
     */
    public ResponsesToMessagesStream(String requestedModel, String messageId) {
        this.requestedModel = requestedModel;
        this.messageId = messageId;
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
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("type", "content_block_delta");
        data.put("index", openIndex);
        data.set("delta", delta);
        frames.add(frame("content_block_delta", data));
    }

    private void onFinal(JsonNode response, List<String> frames) {
        start(frames);
        closeOpen(frames);
        if (nextIndex == 0) {
            // never a zero-block message: the SDKs index content[0]
            openBlock("whole", textBlock(), frames);
            closeOpen(frames);
        }
        ObjectNode delta = JsonNodeFactory.instance.objectNode();
        delta.put("stop_reason", ResponsesToMessages.stopReason(response, false, sawRefusal));
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
        openIndex = nextIndex++;
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("type", "content_block_start");
        data.put("index", openIndex);
        data.set("content_block", block);
        frames.add(frame("content_block_start", data));
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

    /**
     * Both the {@code event:} line and the {@code type} field inside {@code data} are required: the
     * Anthropic SDKs read the latter.
     */
    private static String frame(String name, ObjectNode data) {
        return "event: " + name + "\ndata: " + data + "\n\n";
    }
}

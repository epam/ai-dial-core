package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.server.function.BaseResponseFunction;
import com.epam.aidial.core.server.sse.SseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessagesSseListenerTest {

    @Test
    void testContentFramesAreRelayedWithoutParsing() {
        List<String> seen = new ArrayList<>();
        BaseResponseFunction recorder = new BaseResponseFunction(null, null) {
            @Override
            public Future<JsonNode> apply(JsonNode tree) {
                seen.add(tree.path("type").asText());
                return Future.succeededFuture(tree);
            }
        };
        MessagesSseListener listener = new MessagesSseListener(List.of(recorder));
        List<String> relayed = new ArrayList<>();
        listener.chunkHandler(chunk -> relayed.add(chunk.toString()));

        // the spaces in the data tell a frame relayed as written from one parsed and serialized again
        String delta = "{\"type\": \"content_block_delta\", \"index\": 0}";
        listener.onEvent(event("message_start", "{\"type\": \"message_start\", \"message\": {\"usage\": {\"input_tokens\": 3}}}"));
        listener.onEvent(event("ping", "{\"type\": \"ping\"}"));
        listener.onEvent(event("content_block_start", "{\"type\": \"content_block_start\", \"index\": 0}"));
        listener.onEvent(event("content_block_delta", delta));
        listener.onEvent(event("content_block_stop", "{\"type\": \"content_block_stop\", \"index\": 0}"));
        listener.onEvent(event("message_delta", "{\"type\": \"message_delta\", \"usage\": {\"output_tokens\": 2}}"));
        // an event without an event: line is named message, and still reaches the functions
        listener.onEvent(event("message", "{\"type\": \"message_delta\", \"usage\": {\"output_tokens\": 4}}"));

        assertEquals(List.of("message_start", "message_delta", "message_delta"), seen);
        assertEquals("event: content_block_delta\ndata: " + delta + "\n\n", relayed.get(3));
        assertEquals("event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":2}}\n\n", relayed.get(5));
    }

    private static SseEvent event(String name, String data) {
        return new SseEvent(name, data, null, null);
    }
}

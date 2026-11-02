package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.server.function.BaseResponseFunction;
import com.epam.aidial.core.server.sse.SseEvent;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;

class MessagesSseListener extends BufferingReadStream.BaseEventListener {

    /**
     * Frames no function reads (usage comes in {@code message_start} and {@code message_delta}), relayed as written
     * instead of parsed and serialized again: they are most of a stream and the ones that carry the content. A
     * whitelist, since an event without an {@code event:} line is named {@code message} and may still carry usage.
     */
    private static final Set<String> CONTENT_EVENTS = Set.of("content_block_start", "content_block_delta", "content_block_stop", "ping");

    MessagesSseListener(List<BaseResponseFunction> functions) {
        super(functions);
    }

    @Override
    protected boolean isLastEvent(SseEvent event, JsonNode data) {
        return "message_stop".equals(event.getEvent())
                || (data != null && "message_stop".equals(data.path("type").asText()));
    }

    @Override
    protected boolean skipEvent(SseEvent event) {
        return CONTENT_EVENTS.contains(event.getEvent());
    }
}

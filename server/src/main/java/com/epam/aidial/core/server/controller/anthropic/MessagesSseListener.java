package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.server.function.BaseResponseFunction;
import com.epam.aidial.core.server.sse.SseEvent;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

class MessagesSseListener extends BufferingReadStream.BaseEventListener {

    MessagesSseListener(List<BaseResponseFunction> functions) {
        super(functions);
    }

    @Override
    protected boolean isLastEvent(SseEvent event, JsonNode data) {
        return "message_stop".equals(event.getEvent())
                || (data != null && "message_stop".equals(data.path("type").asText()));
    }
}

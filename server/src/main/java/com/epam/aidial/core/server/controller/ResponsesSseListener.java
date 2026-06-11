package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.function.BaseResponseFunction;
import com.epam.aidial.core.server.sse.SseEvent;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

class ResponsesSseListener extends BufferingReadStream.BaseEventListener {

    ResponsesSseListener(List<BaseResponseFunction> functions) {
        super(functions);
    }

    @Override
    protected boolean isLastEvent(SseEvent event, JsonNode data) {
        return "response.incomplete".equals(event.getEvent()) || "response.completed".equals(event.getEvent());
    }
}

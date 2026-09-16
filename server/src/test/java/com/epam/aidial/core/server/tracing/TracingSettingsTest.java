package com.epam.aidial.core.server.tracing;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TracingSettingsTest {

    @Test
    void everythingIsOptInAndUncorrelatedWhenNothingIsConfigured() {
        TracingSettings settings = TracingSettings.from(new JsonObject());

        assertFalse(settings.genAiSpanAttributes());
        assertFalse(settings.responseTraceHeaders());
        assertEquals(List.of(), settings.conversationIdHeaders());
    }

    @Test
    void readsTheBundledDefaults() {
        TracingSettings settings = TracingSettings.from(new JsonObject()
                .put("genAiSpanAttributes", true)
                .put("responseTraceHeaders", true)
                .put("conversationIdHeaders", new JsonArray()
                        .add("x-claude-code-session-id")
                        .add("thread-id")
                        .add("x-session-id")
                        .add("x-dial-client-channel-id")
                        .add("X-CONVERSATION-ID")));

        assertTrue(settings.genAiSpanAttributes());
        assertTrue(settings.responseTraceHeaders());
        assertEquals(List.of(
                "x-claude-code-session-id",
                "thread-id",
                "x-session-id",
                "x-dial-client-channel-id",
                "X-CONVERSATION-ID"), settings.conversationIdHeaders());
    }

    @Test
    void headerOrderIsPreservedAndBlankOrNonStringEntriesAreDropped() {
        TracingSettings settings = TracingSettings.from(new JsonObject()
                .put("conversationIdHeaders", new JsonArray()
                        .add("thread-id")
                        .add("  ")
                        .add(42)
                        .add(" x-session-id ")));

        assertEquals(List.of("thread-id", "x-session-id"), settings.conversationIdHeaders());
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorization", "Authorization", "api-key", "x-api-key", "cookie", "proxy-authorization"})
    void credentialHeaderNamesAreNeverPublishable(String header) {
        TracingSettings settings = TracingSettings.from(new JsonObject()
                .put("conversationIdHeaders", new JsonArray().add(header).add("thread-id")));

        assertEquals(List.of("thread-id"), settings.conversationIdHeaders());
    }
}

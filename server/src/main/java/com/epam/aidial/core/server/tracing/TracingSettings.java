package com.epam.aidial.core.server.tracing;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Opt-in OpenTelemetry enrichment, from the static {@code tracing} settings.
 *
 * @param conversationIdHeaders request headers, in priority order, that may carry a conversation or session id;
 *                              never null, empty means no correlation is published.
 */
@Slf4j
public record TracingSettings(boolean genAiSpanAttributes, boolean responseTraceHeaders,
                              List<String> conversationIdHeaders) {

    /**
     * Header names whose value must never be published as a conversation id - naming one here
     * would put a credential on the span and on every log record of the request.
     */
    private static final Set<String> CREDENTIAL_HEADERS = Set.of(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "api-key",
            "api_key",
            "x-api-key",
            "x-auth-token",
            "x-amz-security-token");

    public TracingSettings {
        conversationIdHeaders = conversationIdHeaders == null ? List.of() : List.copyOf(conversationIdHeaders);
    }

    public static TracingSettings from(JsonObject settings) {
        return new TracingSettings(
                settings.getBoolean("genAiSpanAttributes", false),
                settings.getBoolean("responseTraceHeaders", false),
                // default is defined in the bundled aidial.settings.json and always merged in
                parseConversationIdHeaders(settings.getJsonArray("conversationIdHeaders")));
    }

    /**
     * One bad entry must not stop the rest from working, so blank and credential header names are
     * dropped with a warning rather than thrown on.
     */
    private static List<String> parseConversationIdHeaders(JsonArray value) {
        List<String> headers = new ArrayList<>();
        if (value == null) {
            return headers;
        }
        for (Object item : value) {
            if (!(item instanceof String s) || s.isBlank()) {
                continue;
            }
            String header = s.trim();
            if (CREDENTIAL_HEADERS.contains(header.toLowerCase(Locale.ROOT))) {
                log.warn("Ignoring conversation id header {}: credential headers are never published", header);
                continue;
            }
            headers.add(header);
        }
        return headers;
    }
}

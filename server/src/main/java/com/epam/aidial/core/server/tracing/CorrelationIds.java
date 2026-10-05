package com.epam.aidial.core.server.tracing;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Trace/user correlation for one outbound upstream call ({@code sendProxyRequest}, {@code handleProxyResponse},
 * {@code handleProxyResponseError}), read once from {@link ProxyContext} and used only as a local variable at
 * the log call site - never stored on {@link ProxyContext}, on a controller field, or in any shared registry.
 *
 * <p>{@link #addTo(LoggingEventBuilder)} attaches these fields to a single log event via SLF4J's fluent
 * {@code Logger.atLevel().addKeyValue(...)} API, which Logback carries on the {@code LoggingEvent} instance
 * itself (see {@code ILoggingEvent.getKeyValuePairs()}). No MDC, {@code ThreadLocal}, Vert.x {@code Context},
 * or OpenTelemetry {@code Context}/{@code Span} is touched - the data exists only for the duration of that one
 * log call and is read back by {@code AutoEnrichedOtelJsonLayout} as a fallback for when {@code ProxyContext}
 * is no longer available (e.g. the client already disconnected and {@code ContextManager.clearContext()} ran).
 */
public record CorrelationIds(String traceId, String spanId, String traceFlags,
                              String conversationId, String project, String userId) {

    public static final String TRACE_ID_KEY = "traceId";
    public static final String SPAN_ID_KEY = "spanId";
    public static final String TRACE_FLAGS_KEY = "traceFlags";
    public static final String CONVERSATION_ID_KEY = "gen_ai.conversation.id";
    public static final String PROJECT_KEY = "user.project";
    public static final String USER_ID_KEY = "user.id";

    public static CorrelationIds from(ProxyContext context) {
        return new CorrelationIds(
                context.getTraceId(),
                context.getSpanId(),
                context.getTraceFlags(),
                context.getRequestHeader(Proxy.HEADER_CONVERSATION_ID),
                context.getProject(),
                context.getUserId());
    }

    public LoggingEventBuilder addTo(LoggingEventBuilder builder) {
        return builder
                .addKeyValue(TRACE_ID_KEY, traceId)
                .addKeyValue(SPAN_ID_KEY, spanId)
                .addKeyValue(TRACE_FLAGS_KEY, traceFlags)
                .addKeyValue(CONVERSATION_ID_KEY, conversationId)
                .addKeyValue(PROJECT_KEY, project)
                .addKeyValue(USER_ID_KEY, userId);
    }
}

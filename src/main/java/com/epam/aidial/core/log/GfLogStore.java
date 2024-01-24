package com.epam.aidial.core.log;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.token.TokenUsage;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.deltix.gflog.api.Log;
import com.epam.deltix.gflog.api.LogEntry;
import com.epam.deltix.gflog.api.LogFactory;
import com.epam.deltix.gflog.api.LogLevel;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Slf4j
public class GfLogStore implements LogStore {

    private static final Log LOGGER = LogFactory.getLog("aidial.log");
    private final Vertx vertx;

    public GfLogStore(Vertx vertx) {
        this.vertx = vertx;
    }

    @Override
    public void save(ProxyContext context) {
        if (!LOGGER.isInfoEnabled() || !context.getRequest().method().equals(HttpMethod.POST)) {
            return;
        }

        vertx.executeBlocking(event -> doSave(context));
    }

    private void doSave(ProxyContext context) {
        LogEntry entry = LOGGER.log(LogLevel.INFO);

        try {
            append(context, entry);
            entry.commit();
        } catch (Throwable e) {
            entry.abort();
            log.warn("Can't save log: {}", e.getMessage());
        }
    }

    private void append(ProxyContext context, LogEntry entry) {
        HttpServerRequest request = context.getRequest();
        HttpServerResponse response = context.getResponse();

        append(entry, "{\"apiType\":\"DialOpenAI\",\"chat\":{\"id\":\"", false);
        append(entry, request.getHeader(Proxy.HEADER_CONVERSATION_ID), true);

        append(entry, "\"},\"project\":{\"id\":\"", false);
        append(entry, context.getProject(), true);

        append(entry, "\"},\"user\":{\"id\":\"", false);
        append(entry, context.getUserHash(), true);

        append(entry, "\",\"title\":\"", false);
        append(entry, request.getHeader(Proxy.HEADER_JOB_TITLE), true);
        append(entry, "\"}", false);

        TokenUsage tokenUsage = context.getTokenUsage();
        if (tokenUsage != null) {
            append(entry, ",\"token_usage\":{", false);
            append(entry, "\"completion_tokens\":", false);
            append(entry, Long.toString(tokenUsage.getCompletionTokens()), true);
            append(entry, ",\"prompt_tokens\":", false);
            append(entry, Long.toString(tokenUsage.getPromptTokens()), true);
            append(entry, ",\"total_tokens\":", false);
            append(entry, Long.toString(tokenUsage.getTotalTokens()), true);
            if (tokenUsage.getCost() != null) {
                append(entry, ",\"deployment_price\":", false);
                append(entry, tokenUsage.getCost().toString(), true);
            }
            if (tokenUsage.getAggCost() != null) {
                append(entry, ",\"price\":", false);
                append(entry, tokenUsage.getAggCost().toString(), true);
            }
            append(entry, "}", false);
        }

        String sourceDeployment = context.getSourceDeployment();
        if (sourceDeployment != null) {
            append(entry, ",\"parent_deployment\":\"", false);
            append(entry, sourceDeployment, true);
            append(entry, "\"", false);
        }

        String executionPath = context.getExecutionPath();
        if (executionPath != null) {
            append(entry, ",\"execution_path\":\"", false);
            append(entry, executionPath, true);
            append(entry, "\"", false);
        }

        append(entry, ",\"trace\":{\"trace_id\":\"", false);
        append(entry, context.getTraceId(), true);

        append(entry, "\",\"core_span_id\":\"", false);
        append(entry, context.getSpanId(), true);

        String parentSpanId = context.getParentSpanId();
        if (parentSpanId != null) {
            append(entry, "\",\"core_parent_span_id\":\"", false);
            append(entry, context.getParentSpanId(), true);
        }

        append(entry, "\"},\"request\":{\"protocol\":\"", false);
        append(entry, request.version().alpnName().toUpperCase(), true);

        append(entry, "\",\"method\":\"", false);
        append(entry, request.method().name(), true);

        append(entry, "\",\"uri\":\"", false);
        append(entry, request.uri(), true);

        append(entry, "\",\"time\":\"", false);
        append(entry, formatTimestamp(context.getRequestTimestamp()), true);

        append(entry, "\",\"body\":\"", false);
        append(entry, context.getRequestBody());

        append(entry, "\"},\"response\":{\"status\":\"", false);
        append(entry, Integer.toString(response.getStatusCode()), true);

        if (context.getUpstreamRoute() != null) {
            append(entry, "\",\"upstream_uri\":\"", false);
            append(entry, context.getUpstreamRoute().get().getEndpoint(), true);
        }

        append(entry, "\",\"body\":\"", false);
        append(entry, context.getResponseBody());

        append(entry, "\"}}", false);
    }


    private static void append(LogEntry entry, Buffer buffer) {
        if (buffer != null) {
            byte[] bytes = buffer.getBytes();
            String chars = new String(bytes, StandardCharsets.UTF_8); // not efficient, but ok for now
            append(entry, chars, true);
        }
    }

    private static void append(LogEntry entry, String chars, boolean escape) {
        if (chars == null) {
            return;
        }

        if (!escape) {
            entry.append(chars);
            return;
        }

        int i;
        int j;

        for (i = 0, j = 0; i < chars.length(); i++) {
            final char c = chars.charAt(i);
            final char e = escape(c);

            if (e != 0) {
                entry.append(chars, j, i);
                entry.append('\\');
                entry.append(e);
                j = i + 1;
            }
        }

        entry.append(chars, j, i);
    }

    private static char escape(char c) {
        return switch (c) {
            case '\b' -> 'b';
            case '\f' -> 'f';
            case '\n' -> 'n';
            case '\r' -> 'r';
            case '\t' -> 't';
            case '"', '\\', '/' -> c;
            default -> 0;
        };
    }

    private static String formatTimestamp(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.of("UTC"))
                .format(DateTimeFormatter.ISO_DATE_TIME);
    }
}

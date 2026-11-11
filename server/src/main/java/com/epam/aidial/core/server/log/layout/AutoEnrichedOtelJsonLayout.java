package com.epam.aidial.core.server.log.layout;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.LayoutBase;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.server.AiDial;
import com.epam.aidial.core.server.ContextManager;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.log.otl.OtelLogRecord;
import com.epam.aidial.core.server.tracing.BackgroundOperationTracer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.event.KeyValuePair;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class AutoEnrichedOtelJsonLayout extends LayoutBase<ILoggingEvent> {

    private final ObjectMapper objectMapper;

    private static final String serviceName = "aidial-core";
    private static final String serviceVersion = AiDial.getVersion();

    public AutoEnrichedOtelJsonLayout() {
        this.objectMapper = new ObjectMapper();
    }

    public String doLayout(ILoggingEvent event) {
        try {
            OtelLogRecord logRecord = buildEnrichedOtelLogRecord(event);
            return objectMapper.writeValueAsString(logRecord) + "\n";
        } catch (JsonProcessingException e) {
            addError("Failed to serialize log event", e);
            return buildFallbackJson(event) + "\n";
        }
    }

    private OtelLogRecord buildEnrichedOtelLogRecord(ILoggingEvent event) {
        String traceId = "";
        String spanId = "";
        String traceFlags = "";

        ProxyContext proxyContext = ContextManager.getProxyContext();
        if (proxyContext != null) {
            traceId = proxyContext.getTraceId();
            spanId = proxyContext.getSpanId();
            traceFlags = proxyContext.getTraceFlags();
        } else {
            // logs of a background run have no request: the run keeps its own trace on its Vert.x context
            SpanContext backgroundTrace = BackgroundOperationTracer.resolveBackgroundTrace().orElse(null);
            if (backgroundTrace != null) {
                traceId = backgroundTrace.getTraceId();
                spanId = backgroundTrace.getSpanId();
                traceFlags = backgroundTrace.getTraceFlags().asHex();
            }
        }

        Map<String, Object> attributes = new LinkedHashMap<>();

        attributes.put("instant", event.getInstant().toString());
        attributes.put("loggerName", event.getLoggerName());
        attributes.put("threadName", event.getThreadName());

        // Enrich attributes from context
        enrichAttributesFromContext(event, attributes);
        
        // Handle exception info
        enrichExceptionAttributes(event, attributes);
        
        // Enrich OpenTelemetry span; a pass served in process for a plugin's call has none: the current one is the plugin's
        if (proxyContext == null || !proxyContext.isChildPass()) {
            enrichOpenTelemetrySpan(event, attributes);
        }

        // after the span pass: GenAiTraceAttributes already set these on the span, typed
        if (proxyContext != null) {
            attributes.putAll(proxyContext.getTracingAttributes());
        }

        // last and log line only: a plugin's fields never overwrite Core's attributes nor reach the span
        enrichKeyValuePairs(event, attributes);

        Map<String, Object> resource = new HashMap<>();
        resource.put("service.name", serviceName);
        resource.put("service.version", serviceVersion);

        long eventTimeNanos = TimeUnit.MILLISECONDS.toNanos(event.getTimeStamp());

        return OtelLogRecord.builder()
                .timestamp(eventTimeNanos)
                .observedTimestamp(eventTimeNanos)
                .severityText(event.getLevel().toString())
                .severityNumber(mapSeverity(event.getLevel().toString()))
                .traceId(traceId)
                .spanId(spanId)
                .traceFlags(traceFlags)
                .body(event.getFormattedMessage())
                .resource(resource)
                .attributes(attributes)
                .build();
    }

    private void enrichAttributesFromContext(ILoggingEvent event, Map<String, Object> attributes) {
        ProxyContext proxyContext = ContextManager.getProxyContext();
        if (proxyContext != null) {
            attributes.put("user.project", proxyContext.getProject());
            attributes.put("user.id", proxyContext.getUserId());
            if (proxyContext.getRequest() != null) {
                attributes.put("request.method", proxyContext.getRequest().method().name());
                attributes.put("request.uri", proxyContext.getRequest().uri());
            }
            if (proxyContext.getResponse().ended()) {
                attributes.put("response.status", proxyContext.getResponse().getStatusMessage());
                attributes.put("response.status.code", proxyContext.getResponse().getStatusCode());
            }
            Route route = proxyContext.getRoute();
            if (route != null) {
                attributes.put("dial.route.name", route.getName());
                attributes.put("dial.route.type", proxyContext.getRouteType().getValue());
            }
        }
    }

    private void enrichExceptionAttributes(ILoggingEvent event, Map<String, Object> attributes) {
        IThrowableProxy throwableProxy = event.getThrowableProxy();
        if (throwableProxy != null) {
            ThrowableProxy throwableProxyImpl = (ThrowableProxy) throwableProxy;
            Throwable throwable = throwableProxyImpl.getThrowable();
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            String stacktrace = sw.toString();

            attributes.put("error", true);
            attributes.put("exception.type", throwableProxy.getClassName());
            attributes.put("exception.message", throwableProxy.getMessage());
            attributes.put("exception.stacktrace", stacktrace);
        }
    }

    /**
     * The record's SLF4J key-value pairs, as an origin plugin writes its fields: under their keys, after Core's own
     * attributes, which a pair cannot replace. A value Jackson has no form for is written as its text, so it never
     * sends the line to the fallback.
     */
    private static void enrichKeyValuePairs(ILoggingEvent event, Map<String, Object> attributes) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs == null) {
            return;
        }
        for (KeyValuePair pair : pairs) {
            Object value = pair.value;
            boolean json = value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                    || value instanceof Collection<?> || value instanceof Map<?, ?> || value instanceof JsonNode;
            // not putIfAbsent, which would replace an attribute Core set to null
            if (!attributes.containsKey(pair.key)) {
                attributes.put(pair.key, json ? value : String.valueOf(value));
            }
        }
    }

    private void enrichOpenTelemetrySpan(ILoggingEvent event, Map<String, Object> attributes) {
        Span currentSpan = Span.current();
        if (!currentSpan.isRecording()) {
            return;
        }

        // Set span attributes from already collected data
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            currentSpan.setAttribute(entry.getKey(), String.valueOf(entry.getValue()));
        }
    }

    private Integer mapSeverity(String level) {
        return switch (level) {
            case "TRACE" -> 1;
            case "DEBUG" -> 5;
            case "INFO" -> 9;
            case "WARN" -> 13;
            case "ERROR" -> 17;
            case "FATAL" -> 21;
            default -> 9;
        };
    }

    private String buildFallbackJson(ILoggingEvent event) {
        return String.format(
                "{\"Timestamp\":\"%s\",\"SeverityText\":\"%s\",\"SeverityNumber\":%d,\"Body\":\"%s\",\"Logger\":\"%s\"}",
                Instant.ofEpochMilli(event.getTimeStamp()),
                event.getLevel().toString(),
                mapSeverity(event.getLevel().toString()),
                event.getFormattedMessage().replace("\"", "\\\""),
                event.getLoggerName()
        );
    }
}

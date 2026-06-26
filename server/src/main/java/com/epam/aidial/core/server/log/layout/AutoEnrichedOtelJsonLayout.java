package com.epam.aidial.core.server.log.layout;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.LayoutBase;
import com.epam.aidial.core.server.AiDial;
import com.epam.aidial.core.server.ContextManager;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.log.otl.OtelLogRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        }

        Map<String, Object> attributes = new LinkedHashMap<>();

        attributes.put("instant", event.getInstant().toString());
        attributes.put("loggerName", event.getLoggerName());
        attributes.put("threadName", event.getThreadName());

        // Enrich attributes from context
        enrichAttributesFromContext(event, attributes);
        
        // Handle exception info
        enrichExceptionAttributes(event, attributes);
        
        // Enrich OpenTelemetry span
        enrichOpenTelemetrySpan(event, attributes);

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


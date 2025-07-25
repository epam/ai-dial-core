package com.epam.aidial.core.server.log.layout;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.LayoutBase;
import com.epam.aidial.core.server.AiDial;
import com.epam.aidial.core.server.ContextManager;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.log.otl.OtelLogRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class AutoEnrichedOtelJsonLayout extends LayoutBase<ILoggingEvent> {

    private final ObjectMapper objectMapper;
    @Setter
    private String serviceName = "aidial-core";
    @Setter
    private String serviceVersion = AiDial.getVersion();

    public AutoEnrichedOtelJsonLayout() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
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
        Map<String, String> mdc = event.getMDCPropertyMap();

        // Get trace context from MDC or current span
        String traceId = mdc.get("trace.id");
        String spanId = mdc.get("span.id");
        String traceFlags = mdc.get("trace.flags");

        // If trace context is not in MDC, try to get from current span
        if (traceId == null || spanId == null) {
            try {
                Span currentSpan = Span.current();
                SpanContext spanContext = currentSpan.getSpanContext();
                if (spanContext.isValid()) {
                    traceId = spanContext.getTraceId();
                    spanId = spanContext.getSpanId();
                    traceFlags = spanContext.getTraceFlags().asHex();
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        Map<String, String> mdcCopy = new HashMap<>(mdc);

        String[] fieldsToRemove = {"trace.id", "span.id", "trace.flags"};
        for (String field : fieldsToRemove) {
            mdcCopy.remove(field);
        }

        Map<String, Object> attributes = new HashMap<>();
        if (!mdcCopy.isEmpty()) {
            attributes.putAll(mdcCopy);
        }

        // Always enrich attributes from Vertx context and ProxyContext
        enrichAttributesFromContext(attributes);

        // Handle exception info
        enrichExceptionAttributes(event, attributes);
        autoEnrichOpenTelemetry(event);

        Map<String, Object> resource = new HashMap<>();
        resource.put("service.name", serviceName);
        resource.put("service.version", serviceVersion);

        long currentTimeNanos = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());

        return OtelLogRecord.builder()
                .timestamp(currentTimeNanos)
                .observedTimestamp(currentTimeNanos)
                .severityText(event.getLevel().toString())
                .severityNumber(mapSeverity(event.getLevel().toString()))
                .traceId(traceId != null ? traceId : "")
                .spanId(spanId != null ? spanId : "")
                .traceFlags(traceFlags != null ? traceFlags : "")
                .body(event.getFormattedMessage())
                .resource(resource)
                .attributes(attributes)
                .build();
    }

    private void enrichAttributesFromContext(Map<String, Object> attributes) {
        // First try to get values from Vertx context
        String userProject = getContextValue("user.project");
        String userSub = getContextValue("user.sub");
        String requestUri = getContextValue("request.uri");
        String requestMethod = getContextValue("request.method");
        String httpStatusCode = getContextValue("http.status.code");

        // Then try to get from ProxyContext if not found
        try {
            ProxyContext proxyContext = ContextManager.getProxyContext();
            if (proxyContext != null) {
                if (userProject == null && proxyContext.getProject() != null) {
                    userProject = proxyContext.getProject();
                }
                if (userSub == null && proxyContext.getUserSub() != null) {
                    userSub = proxyContext.getUserSub();
                }
                if (requestUri == null && proxyContext.getRequest() != null) {
                    requestUri = proxyContext.getRequest().uri();
                }
                if (requestMethod == null && proxyContext.getRequest() != null) {
                    requestMethod = proxyContext.getRequest().method().name();
                }
            }
        } catch (Exception e) {
            // Ignore
        }

        // Always add these fields, use "unknown" as default if not available
        attributes.put("user.project", userProject != null ? userProject : "unknown");
        attributes.put("user.sub", userSub != null ? userSub : "unknown");
        attributes.put("request.uri", requestUri != null ? requestUri : "unknown");
        attributes.put("request.method", requestMethod != null ? requestMethod : "unknown");

        // Only add http.status.code if it's available (it won't be available before response is sent)
        if (httpStatusCode != null) {
            attributes.put("http.status.code", httpStatusCode);
        }
    }

    private void enrichExceptionAttributes(ILoggingEvent event, Map<String, Object> attributes) {
        IThrowableProxy throwableProxy = event.getThrowableProxy();
        if (throwableProxy != null) {
            attributes.put("exception.type", throwableProxy.getClassName());
            attributes.put("exception.message", throwableProxy.getMessage() != null ? throwableProxy.getMessage() : "");
            attributes.put("exception.stacktrace", Arrays.toString(throwableProxy.getStackTraceElementProxyArray()));
        }
    }

    private String getContextValue(String key) {
        try {
            Context vertxContext = Vertx.currentContext();
            if (vertxContext != null) {
                Object localValue = vertxContext.getLocal(key);
                if (localValue != null) {
                    return localValue.toString();
                }
            }
        } catch (Exception e) {
            // Ignore
        }

        return null;
    }

    private void autoEnrichOpenTelemetry(ILoggingEvent event) {
        try {
            Span currentSpan = Span.current();
            if (currentSpan.isRecording()) {
                String userProject = getContextValue("user.project");
                String userSub = getContextValue("user.sub");
                String requestUri = getContextValue("request.uri");
                String requestMethod = getContextValue("request.method");

                // Always enrich span with context attributes
                currentSpan.setAllAttributes(Attributes.of(
                        AttributeKey.stringKey("request.uri"), requestUri != null ? requestUri : "unknown",
                        AttributeKey.stringKey("request.method"), requestMethod != null ? requestMethod : "unknown",
                        AttributeKey.stringKey("user.project"), userProject != null ? userProject : "unknown",
                        AttributeKey.stringKey("user.sub"), userSub != null ? userSub : "unknown"
                ));

                // Additionally handle exception if present
                IThrowableProxy throwableProxy = event.getThrowableProxy();
                if (throwableProxy != null) {
                    RuntimeException wrapper = new RuntimeException(throwableProxy.getMessage());
                    currentSpan.recordException(wrapper, Attributes.of(
                            AttributeKey.stringKey("exception.type"), throwableProxy.getClassName(),
                            AttributeKey.stringKey("exception.message"), throwableProxy.getMessage() != null ? throwableProxy.getMessage() : ""
                    ));
                    // Only set ERROR status for ERROR level logs, not for WARN
                    if (event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR)) {
                        currentSpan.setStatus(StatusCode.ERROR, throwableProxy.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            // Ignore enrich exceptions
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
                "{\"timestamp\":\"%s\",\"severity\":%d,\"message\":\"%s\",\"logger\":\"%s\"}",
                Instant.ofEpochMilli(event.getTimeStamp()),
                mapSeverity(event.getLevel().toString()),
                event.getFormattedMessage().replace("\"", "\\\""),
                event.getLoggerName()
        );
    }
}
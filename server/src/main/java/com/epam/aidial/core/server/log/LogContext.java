package com.epam.aidial.core.server.log;

import lombok.Value;

import java.util.Map;

/**
 * A snapshot of {@link LogAttributes} that does not hold the request and response bodies a ProxyContext does.
 * It replaces the ProxyContext in the Vert.x context once the connection closes, so log lines written after that
 * keep their attributes while the bodies can be collected.
 */
@Value
public class LogContext implements LogAttributes {
    String traceId;
    String spanId;
    String traceFlags;
    String project;
    String userId;
    String requestMethod;
    String requestUri;
    boolean responseEnded;
    String statusMessage;
    int statusCode;
    Map<String, Object> tracingAttributes;

    public static LogContext of(LogAttributes source) {
        boolean ended = source.isResponseEnded();
        return new LogContext(source.getTraceId(), source.getSpanId(), source.getTraceFlags(),
                source.getProject(), source.getUserId(), source.getRequestMethod(), source.getRequestUri(),
                ended, ended ? source.getStatusMessage() : null, ended ? source.getStatusCode() : 0,
                source.getTracingAttributes());
    }
}

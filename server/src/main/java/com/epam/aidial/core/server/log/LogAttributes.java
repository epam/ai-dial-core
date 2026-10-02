package com.epam.aidial.core.server.log;

import java.util.Map;

/**
 * What a log record needs from the request it belongs to. Implemented by the live
 * {@link com.epam.aidial.core.server.ProxyContext} and, once that is cleared, by the {@link LogContext} snapshot.
 */
public interface LogAttributes {

    String getTraceId();

    String getSpanId();

    String getTraceFlags();

    String getProject();

    String getUserId();

    /**
     * @return null when the request is not known
     */
    String getRequestMethod();

    String getRequestUri();

    boolean isResponseEnded();

    /**
     * Meaningful only when {@link #isResponseEnded()}.
     */
    String getStatusMessage();

    /**
     * Meaningful only when {@link #isResponseEnded()}.
     */
    int getStatusCode();

    /**
     * The request's live attribute map: attributes set later still reach the log records.
     */
    Map<String, Object> getTracingAttributes();
}

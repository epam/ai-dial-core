package com.epam.aidial.core.server.log;

public interface LogStore {

    void save(AnalyticsLogContext logContext);

    /**
     * @return true if a response with this status - one that reaches the client only via a {@code respond(...)}
     *     error exit (rate limit hit, retries exhausted, connection failure) and therefore carries no analytics
     *     log entry otherwise - should get one anyway.
     */
    boolean shouldLogErrorResponse(int statusCode);
}

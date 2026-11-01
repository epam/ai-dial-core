package com.epam.aidial.core.server.log;

import lombok.SneakyThrows;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps every analytics row Core writes, so a test can read them, and passes each on to the store that writes it.
 */
public class RecordingLogStore implements LogStore {

    private final LogStore delegate;
    private final List<AnalyticsLogContext> rows = new CopyOnWriteArrayList<>();

    public RecordingLogStore(LogStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public void save(AnalyticsLogContext logContext) {
        rows.add(logContext);
        delegate.save(logContext);
    }

    /**
     * Waits for {@code count} rows of requests to {@code requestUri}, then a little longer, so a row written twice
     * shows up, and returns all of them. A row is written after the usage is charged, so the limit records are up
     * to date once it is here.
     */
    @SneakyThrows
    public List<AnalyticsLogContext> await(String requestUri, int count) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (rows(requestUri).size() < count) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Expected " + count + " analytics row(s) of " + requestUri + ", got " + rows(requestUri).size());
            }
            Thread.sleep(50);
        }
        Thread.sleep(300);
        return rows(requestUri);
    }

    private List<AnalyticsLogContext> rows(String requestUri) {
        return rows.stream().filter(row -> requestUri.equals(row.getRequestUri())).toList();
    }
}

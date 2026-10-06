package com.epam.aidial.core.server.log;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.epam.aidial.core.server.ContextManager;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.log.layout.AutoEnrichedOtelJsonLayout;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Captures what the console appender prints: every log line rendered by {@link AutoEnrichedOtelJsonLayout} on the
 * logging thread, so the enrichment sees the same Vert.x context as in production.
 */
public class JsonLogCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final AutoEnrichedOtelJsonLayout layout = new AutoEnrichedOtelJsonLayout();
    private final List<JsonNode> lines = new CopyOnWriteArrayList<>();
    private final List<WeakReference<ProxyContext>> proxyContexts = new CopyOnWriteArrayList<>();

    public static JsonLogCapture attach() {
        JsonLogCapture capture = new JsonLogCapture();
        capture.setContext(capture.root.getLoggerContext());
        capture.layout.setContext(capture.root.getLoggerContext());
        capture.start();
        capture.root.addAppender(capture);
        return capture;
    }

    @Override
    @SneakyThrows
    protected void append(ILoggingEvent event) {
        ProxyContext proxyContext = ContextManager.getProxyContext();
        if (proxyContext != null) {
            proxyContexts.add(new WeakReference<>(proxyContext));
        }
        lines.add(MAPPER.readTree(layout.doLayout(event)));
    }

    /**
     * Weak references to every ProxyContext the logging thread saw, to check they are released once their
     * requests are over.
     */
    public List<WeakReference<ProxyContext>> proxyContexts() {
        return proxyContexts;
    }

    /**
     * Waits for the first ProxyContext a log line of the request under test was enriched from.
     */
    @SneakyThrows
    public ProxyContext awaitProxyContext() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            for (WeakReference<ProxyContext> reference : proxyContexts) {
                ProxyContext proxyContext = reference.get();
                if (proxyContext != null) {
                    return proxyContext;
                }
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("No log line was enriched from a ProxyContext. Captured bodies: "
                        + lines.stream().map(line -> line.path("Body").asText()).toList());
            }
            Thread.sleep(50);
        }
    }

    /**
     * Waits for {@code count} lines whose body starts with {@code bodyPrefix}; late callbacks log after the client
     * call has already returned.
     */
    @SneakyThrows
    public List<JsonNode> await(String bodyPrefix, int count) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            List<JsonNode> found = lines.stream()
                    .filter(line -> line.path("Body").asText().startsWith(bodyPrefix))
                    .toList();
            if (found.size() >= count) {
                return found;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Expected " + count + " log line(s) starting with '" + bodyPrefix + "', got "
                        + found.size() + ". Captured bodies: " + lines.stream().map(line -> line.path("Body").asText()).toList());
            }
            Thread.sleep(50);
        }
    }

    public JsonNode await(String bodyPrefix) {
        return await(bodyPrefix, 1).get(0);
    }

    @Override
    public void close() {
        root.detachAppender(this);
        stop();
    }
}

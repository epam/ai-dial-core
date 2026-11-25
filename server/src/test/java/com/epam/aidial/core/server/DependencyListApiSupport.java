package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * What the API tests of {@code POST /v1/ops/dependency/list} share: the path, the body of a request, a blob stored as it is, and a capture
 * of the log of the process.
 */
final class DependencyListApiSupport {

    static final String PATH = "/v1/ops/dependency/list";

    private DependencyListApiSupport() {
    }

    static String rootsRequest(String... urls) {
        StringBuilder request = new StringBuilder("{\"roots\":[");
        for (int i = 0; i < urls.length; i++) {
            request.append(i == 0 ? "" : ",").append("{\"type\":\"application\",\"url\":\"").append(urls[i]).append("\"}");
        }
        return request.append("]}").toString();
    }

    /** Stores a JSON blob as it is at a path of the blob storage, without going through the API. */
    static void storeJson(AiDial dial, String blobPath, String json) {
        dial.getStorage().store(blobPath, "application/json", null, Map.of(), json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The log events of the whole process while it is open, because the server runs in the JVM of the test; the lines read as
     * {@code LEVEL message}.
     */
    static final class Logs extends AppenderBase<ILoggingEvent> implements AutoCloseable {

        private final ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();
        private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

        Logs() {
            setContext(root.getLoggerContext());
            start();
            root.addAppender(this);
        }

        @Override
        protected void append(ILoggingEvent event) {
            StringBuilder line = new StringBuilder(event.getLevel().toString()).append(' ').append(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                line.append(' ').append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
            lines.add(line.toString());
        }

        List<String> lines(Level level, String messagePrefix) {
            return lines.stream().filter(line -> line.startsWith(level + " " + messagePrefix)).toList();
        }

        String text() {
            return String.join("\n", lines);
        }

        @Override
        public void close() {
            root.detachAppender(this);
            stop();
        }
    }
}

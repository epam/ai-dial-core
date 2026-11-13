package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.storage.service.LockService;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the API tests of {@code POST /v1/admin/export} share: the request, the entries of the ZIP, a client that pauses after the response
 * head, the wait for the bucket locks, and a capture of the log of the process.
 */
final class ExportApiSupport {

    static final String PATH = "/v1/admin/export";

    private ExportApiSupport() {
    }

    /**
     * @param body the archive as received; for a status response it is the text of the answer
     */
    record Reply(int status, String contentType, String disposition, byte[] body) {

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        Map<String, byte[]> entries() throws IOException {
            return unzip(body);
        }
    }

    /** Posts as the admin and reads the whole answer; a stream cut after the head fails here with an {@link IOException}. */
    static Reply post(int serverPort, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + PATH))
                .version(HttpClient.Version.HTTP_1_1).header("authorization", "admin")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse("-"),
                response.headers().firstValue("Content-Disposition").orElse("-"), response.body());
    }

    /**
     * Posts a request whose export fails after the checks: no complete archive arrives. A stream cut after the first byte is an {@link IOException};
     * a failure before it is answered 500.
     */
    static void assertExportFails(int serverPort, String body) throws InterruptedException {
        try {
            Reply reply = post(serverPort, body);
            assertEquals(500, reply.status(), "a failure before the first byte is answered 500");
            assertFalse(reply.contentType().contains("zip"), "no archive");
        } catch (IOException e) {
            // the stream was cut
        }
    }

    static String rootsRequest(String... urls) {
        StringBuilder request = new StringBuilder("{\"roots\":[");
        for (int i = 0; i < urls.length; i++) {
            request.append(i == 0 ? "" : ",").append("{\"type\":\"application\",\"url\":\"").append(urls[i]).append("\"}");
        }
        return request.append("]}").toString();
    }

    static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    /** The entries that are not the manifest: the resources of the archive. */
    static List<String> resourceNames(Map<String, byte[]> entries) {
        return entries.keySet().stream().filter(name -> !name.equals(ExportArchive.MANIFEST_ENTRY)).toList();
    }

    /** Stores a JSON blob as it is at a path of the blob storage, without going through the API. */
    static void storeJson(AiDial dial, String blobPath, String json) {
        dial.getStorage().store(blobPath, "application/json", null, Map.of(), json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Waits until the bucket locks can be taken, and releases them again: the writer of an export that ended after the client had gone frees
     * them a moment later, not at the end of their lease.
     */
    static void awaitLocksReleased(LockService lockService) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            LockService.Lock locks = lockService.tryLockBuckets(MergedConfigStore.ADMIN_BUCKET_LOCATIONS);
            if (locks != null) {
                locks.close();
                return;
            }
            assertTrue(System.nanoTime() < deadline, "the export should release its bucket locks");
            Thread.sleep(50);
        }
    }

    record PausedStream(HttpClientRequest request, HttpClientResponse response, Buffer received, Future<Void> finished) {
    }

    /**
     * Returns when the response head has arrived, with the body paused. The handlers are set on the event loop together with the pause,
     * otherwise the first chunks are dropped.
     */
    static PausedStream connectPaused(io.vertx.core.http.HttpClient client, int serverPort, String body) throws Exception {
        RequestOptions options = new RequestOptions().setAbsoluteURI("http://127.0.0.1:" + serverPort + PATH).setMethod(HttpMethod.POST)
                .putHeader("authorization", "admin");
        return await(client.request(options).compose(request -> request.send(Buffer.buffer(body)).map(response -> {
            response.pause();
            Buffer received = Buffer.buffer();
            Promise<Void> finished = Promise.promise();
            response.handler(received::appendBuffer);
            response.endHandler(ignored -> finished.tryComplete());
            response.exceptionHandler(finished::tryFail);
            return new PausedStream(request, response, received, finished.future());
        })));
    }

    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(120, TimeUnit.SECONDS);
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

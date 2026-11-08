package com.epam.aidial.core.server;

import com.epam.aidial.core.server.controller.AdminExportController;
import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.server.service.ExportArchive.BodyRedactor;
import com.epam.aidial.core.storage.service.LockService;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens when the streaming of a selective Application export fails: a client that stops reading, a
 * client that goes away after the response head, and a client that goes away while the archive is still being prepared. The
 * failure of a write in the middle of the stream is tested on the archive in {@code ExportArchiveTest}. The stall timeout is
 * short, so that a client that stops reading fails the export within the test.
 *
 * <p>The selective export holds no bucket lock, so the end of its writer cannot be seen as a released lock the way
 * {@code AdminSystemExportApiTest} sees it. The tests count the tasks that the executor has finished instead
 * ({@code dial_async_task_duration}): a request runs three, the planning, the preparation of the archive and its writer. The
 * counter is global to the JVM, so it is a proxy and not a proof that the writer ended; what each test asserts about the
 * client and about the next request does not depend on it.
 *
 * <p>The package is large and does not compress (random text), so the writer cannot finish into the buffers of the pipe and
 * the connection while the client does not read.
 */
public class ExportSelectionStreamFailureApiTest extends ResourceBaseTest {

    private static final String PATH = "/v1/admin/export";
    private static final int DEPENDENCIES = 8;
    private static final int DEPENDENCY_BYTES = 6 * 1024 * 1024;
    private static final String CRLF = "\r\n";
    private static final String LARGE_REQUEST = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/large-root\"}]}";
    private static final String SMALL_REQUEST = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/small-root\"}]}";
    private static final BodyRedactor PASS_THROUGH = (identity, body) -> body.getBytes(StandardCharsets.UTF_8);

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("adminExport", new JsonObject().put("stallTimeout", 2000).put("maxResourceMiB", 8).put("maxRetainedMiB", 64));
    }

    /** The global registry has no registry in the test server, so its timers record nothing until one is added. */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @AfterEach
    void removeMeterRegistry() {
        Metrics.removeRegistry(meterRegistry);
    }

    @BeforeEach
    void setUpPackages() throws IOException {
        Metrics.addRegistry(meterRegistry);
        raiseRedisMemoryLimit();
        dial.getProxy().setExportBodyRedactor(PASS_THROUGH);
        Random random = new Random(1);
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < DEPENDENCIES; i++) {
            byte[] noise = new byte[DEPENDENCY_BYTES / 4 * 3];
            random.nextBytes(noise);
            store("dependency-" + i, "{\"endpoint\":\"http://dependency.invalid/chat\",\"description\":\""
                    + Base64.getEncoder().encodeToString(noise) + "\"}");
            urls.add("\"applications/platform/dependency-" + i + "\"");
        }
        store("large-root", "{\"endpoint\":\"http://root.invalid/chat\",\"dependencies\":[" + String.join(",", urls) + "]}");
        store("small-root", "{\"endpoint\":\"http://small.invalid/chat\"}");
    }

    private void store(String name, String json) {
        dial.getStorage().store("platform/applications/" + name, "application/json", null, Map.of(), json.getBytes(StandardCharsets.UTF_8));
    }

    /** The embedded Redis of the test base is capped at 16 MB, which bodies of this size exceed; a limit of the harness only. */
    private void raiseRedisMemoryLimit() throws IOException {
        String command = "*4" + CRLF + "$6" + CRLF + "CONFIG" + CRLF + "$3" + CRLF + "SET" + CRLF + "$9" + CRLF + "maxmemory" + CRLF
                + "$5" + CRLF + "768mb" + CRLF;
        try (Socket socket = new Socket("localhost", redis.ports().get(0))) {
            socket.getOutputStream().write(command.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertEquals("+OK" + CRLF, new String(socket.getInputStream().readNBytes(5), StandardCharsets.US_ASCII));
        }
    }

    // ---- the client

    private record Stream(HttpClientRequest request, HttpClientResponse response, Buffer received, Future<Void> finished) {
    }

    private RequestOptions options() {
        return new RequestOptions().setAbsoluteURI("http://127.0.0.1:" + serverPort + PATH).setMethod(HttpMethod.POST)
                .putHeader("authorization", "admin");
    }

    /**
     * Sends the request and returns when the response head has arrived, with the body paused. The handlers are set on the event
     * loop together with the pause, otherwise the first chunks are dropped.
     */
    private Stream connect(String body) throws Exception {
        return await(dial.getClient().request(options()).compose(request -> request.send(Buffer.buffer(body)).map(response -> {
            response.pause();
            Buffer received = Buffer.buffer();
            Promise<Void> finished = Promise.promise();
            response.handler(received::appendBuffer);
            response.endHandler(ignored -> finished.tryComplete());
            response.exceptionHandler(finished::tryFail);
            return new Stream(request, response, received, finished.future());
        })));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }

    private record Download(int status, String contentType, byte[] body) {
    }

    private Download post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + PATH))
                .version(HttpClient.Version.HTTP_1_1).header("authorization", "admin").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new Download(response.statusCode(), response.headers().firstValue("Content-Type").orElse("-"), response.body());
    }

    private static List<String> entryNames(byte[] archive) throws IOException {
        List<String> result = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                result.add(entry.getName());
                zip.readAllBytes();
            }
        }
        return result;
    }

    // ---- the executor

    private static long finishedTasks() {
        return Metrics.globalRegistry.get("dial_async_task_duration").timer().count();
    }

    private void awaitFinishedTasks(long atLeast) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (finishedTasks() < atLeast) {
            assertTrue(System.nanoTime() < deadline, "the writer of the export should end");
            Thread.sleep(50);
        }
        // the counted tasks include those of the reader, so the writer may still be ending: wait until it has released the slot
        while (true) {
            LockService.Lock slot = dial.getProxy().getLockService().tryLock(AdminExportController.SELECTION_SLOT);
            if (slot != null) {
                slot.close();
                return;
            }
            assertTrue(System.nanoTime() < deadline, "the export should release its slot");
            Thread.sleep(50);
        }
    }

    // ---- the tests

    @Test
    void testReadingClientGetsTheCompleteLargePackage() throws Exception {
        Download download = post(LARGE_REQUEST);

        assertEquals(200, download.status());
        assertEquals("application/zip", download.contentType());
        List<String> names = entryNames(download.body());
        assertEquals(2 * DEPENDENCIES + 5, names.size(), "the manifest, the root, its dependencies and the verification entry");
        assertEquals(ExportArchive.MANIFEST_ENTRY, names.get(0));
        assertEquals(ExportArchive.VERIFICATION_ENTRY, names.get(names.size() - 1));
        assertTrue(download.body().length > DEPENDENCIES * (long) DEPENDENCY_BYTES / 2, "the package is large: " + download.body().length);
    }

    @Test
    void testStalledClientResetsTheSelectiveExportAndGetsNoCentralDirectory() throws Exception {
        long before = finishedTasks();
        Stream stream = connect(LARGE_REQUEST);
        assertEquals(200, stream.response().statusCode());
        assertEquals("application/zip", stream.response().getHeader("Content-Type"));

        Thread.sleep(3500); // longer than the stall timeout of 2 s, with the client not reading
        awaitFinishedTasks(before + 3);
        stream.response().resume();

        assertThrows(ExecutionException.class, () -> await(stream.finished()), "the response is reset, never ended cleanly");
        String partial = new String(stream.received().getBytes(), StandardCharsets.ISO_8859_1);
        assertTrue(partial.startsWith("PK\u0003\u0004"), "what arrived is the beginning of a ZIP");
        assertTrue(partial.contains(ExportArchive.MANIFEST_ENTRY), "the manifest is the first entry");
        assertFalse(partial.contains("PK\u0005\u0006"), "no end of central directory arrived");
        assertFalse(partial.contains(ExportArchive.VERIFICATION_ENTRY), "the verification entry was not reached");
    }

    @Test
    void testClientDisconnectAfterTheHeadEndsTheWriterAndTheNextExportSucceeds() throws Exception {
        long before = finishedTasks();
        Stream stream = connect(LARGE_REQUEST);
        assertEquals(200, stream.response().statusCode());

        stream.request().connection().close();

        awaitFinishedTasks(before + 3);
        Download next = post(SMALL_REQUEST);
        assertEquals(200, next.status());
        assertEquals(List.of(ExportArchive.MANIFEST_ENTRY, ExportArchive.REGISTRY_ENTRY,
                ".dial-system-export/metadata/platform/applications/small-root", "platform/applications/small-root", ExportArchive.VERIFICATION_ENTRY),
                entryNames(next.body()));
    }

    @Test
    void testClientDisconnectWhileTheArchiveIsPreparedStartsNoLongStreamAndTheNextExportSucceeds() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        dial.getProxy().setExportBodyRedactor((identity, body) -> {
            entered.countDown();
            try {
                assertTrue(release.await(30, TimeUnit.SECONDS), "the test should release the redactor");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return body.getBytes(StandardCharsets.UTF_8);
        });
        long before = finishedTasks();
        HttpClientRequest request = await(dial.getClient().request(options()).map(created -> {
            created.send(Buffer.buffer(LARGE_REQUEST));
            return created;
        }));
        assertTrue(entered.await(30, TimeUnit.SECONDS), "the archive should be in preparation");

        request.connection().close();
        Thread.sleep(500); // the server has seen the connection close before the archive is ready
        release.countDown();

        awaitFinishedTasks(before + 3);
        dial.getProxy().setExportBodyRedactor(PASS_THROUGH);
        Download next = post(SMALL_REQUEST);
        assertEquals(200, next.status());
        assertEquals(5, entryNames(next.body()).size());
    }
}

package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import com.epam.aidial.core.server.ExportApiSupport.Logs;
import com.epam.aidial.core.server.ExportApiSupport.Reply;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.storage.service.LockService;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens when the streaming of a selective export fails: a client that stops reading, a client that goes away after the head, and a
 * bucket lock held by someone else. A failing write in the middle is tested in {@code ExportArchiveTest}. The stall timeout is short, so
 * that a client that stops reading fails the export within the test.
 *
 * <p>The end of the writer is seen as released locks, as {@code AdminSystemExportApiTest} sees it. The package is large random text, so it
 * does not compress and the writer cannot finish into the buffers while the client does not read.
 */
public class ExportSelectionStreamFailureApiTest extends ResourceBaseTest {

    private static final int DEPENDENCIES = 8;
    private static final int DEPENDENCY_BYTES = 6 * 1024 * 1024;
    private static final String CRLF = "\r\n";
    private static final String LARGE_REQUEST = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/large-root\"}]}";
    private static final String SMALL_REQUEST = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/small-root\"}]}";

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("adminExport", new JsonObject().put("stallTimeout", 2000));
    }

    @BeforeEach
    void setUpPackages() throws IOException {
        raiseRedisMemoryLimit();
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
        ExportApiSupport.storeJson(dial, "platform/applications/" + name, json);
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

    private ExportApiSupport.PausedStream connect(String body) throws Exception {
        return ExportApiSupport.connectPaused(dial.getClient(), serverPort, body);
    }

    private LockService lockService() {
        return dial.getProxy().getLockService();
    }

    @Test
    void testReadingClientGetsTheCompleteLargePackageAndTheLocksAreReleased() throws Exception {
        Reply download = ExportApiSupport.post(serverPort, LARGE_REQUEST);

        assertEquals(200, download.status());
        assertEquals("application/zip", download.contentType());
        List<String> names = List.copyOf(download.entries().keySet());
        assertEquals(DEPENDENCIES + 2, names.size(), "the manifest, the root and its dependencies");
        assertEquals(ExportArchive.MANIFEST_ENTRY, names.get(0));
        assertTrue(download.body().length > DEPENDENCIES * (long) DEPENDENCY_BYTES / 2, "the package is large: " + download.body().length);
        ExportApiSupport.awaitLocksReleased(lockService());
    }

    @Test
    void testHeldBucketLockIsConflictBeforeAnyByteAndTheExportRunsOnceItIsFree() throws Exception {
        LockService.Lock held = lockService().tryLockBuckets(MergedConfigStore.ADMIN_BUCKET_LOCATIONS);
        assertNotNull(held);
        try (Logs logs = new Logs()) {
            Reply refused = ExportApiSupport.post(serverPort, SMALL_REQUEST);

            assertEquals(409, refused.status());
            assertFalse(new String(refused.body(), StandardCharsets.ISO_8859_1).contains("PK"), "no archive byte was sent");
            assertEquals(List.of("INFO Admin export rejected: a bucket lock is held by another export, a system export or a write"),
                    logs.lines(Level.INFO, "Admin export rejected"));
            assertTrue(logs.lines(Level.INFO, "Export started").isEmpty(), "the refused export did not start");
        } finally {
            held.close();
        }

        Reply next = ExportApiSupport.post(serverPort, SMALL_REQUEST);
        assertEquals(200, next.status());
        ExportApiSupport.awaitLocksReleased(lockService());
    }

    @Test
    void testRootThatDoesNotExistIsNotFoundBeforeAnyByteAndTheLocksAreReleased() throws Exception {
        Reply missing = ExportApiSupport.post(serverPort, "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/no-such-root\"}]}");

        assertEquals(404, missing.status());
        assertFalse(new String(missing.body(), StandardCharsets.ISO_8859_1).contains("PK"), "no archive byte was sent");
        ExportApiSupport.awaitLocksReleased(lockService());
        assertEquals(200, ExportApiSupport.post(serverPort, SMALL_REQUEST).status());
    }

    @Test
    void testStalledClientResetsTheSelectiveExportAndGetsNoCentralDirectory() throws Exception {
        ExportApiSupport.PausedStream stream = connect(LARGE_REQUEST);
        assertEquals(200, stream.response().statusCode());
        assertEquals("application/zip", stream.response().getHeader("Content-Type"));

        Thread.sleep(3500); // longer than the stall timeout of 2 s, with the client not reading
        ExportApiSupport.awaitLocksReleased(lockService());
        stream.response().resume();

        assertThrows(ExecutionException.class, () -> ExportApiSupport.await(stream.finished()), "the response is reset, never ended cleanly");
        String partial = new String(stream.received().getBytes(), StandardCharsets.ISO_8859_1);
        assertTrue(partial.startsWith("PK"), "what arrived is the beginning of a ZIP");
        assertTrue(partial.contains(ExportArchive.MANIFEST_ENTRY), "the manifest is the first entry");
        assertFalse(partial.contains("PK"), "no end of central directory arrived");
    }

    @Test
    void testClientDisconnectAfterTheHeadEndsTheWriterReleasesTheLocksAndTheNextExportSucceeds() throws Exception {
        ExportApiSupport.PausedStream stream = connect(LARGE_REQUEST);
        assertEquals(200, stream.response().statusCode());

        stream.request().connection().close();

        ExportApiSupport.awaitLocksReleased(lockService());
        Reply next = ExportApiSupport.post(serverPort, SMALL_REQUEST);
        assertEquals(200, next.status());
        assertEquals(List.of(ExportArchive.MANIFEST_ENTRY, "platform/applications/small-root"), List.copyOf(next.entries().keySet()));
    }
}

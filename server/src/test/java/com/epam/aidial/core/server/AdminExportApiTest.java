package com.epam.aidial.core.server;

import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.service.LockService;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@code POST /v1/admin/export}. The stall timeout is short, so that a client
 * that stops reading fails the export within the test.
 */
public class AdminExportApiTest extends ResourceBaseTest {

    private static final int LARGE_BLOBS = 32;
    private static final int LARGE_BLOB_SIZE = 2 * 1024 * 1024;

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("adminExport", new JsonObject().put("stallTimeout", 2000));
    }

    @Test
    void testExportIsForbiddenForNonAdmin() {
        verify(send(HttpMethod.POST, "/v1/admin/export", null, "", "authorization", "user"), 403);
        verify(send(HttpMethod.POST, "/v1/admin/export", null, "", "Api-key", "proxyKey1"), 403);
    }

    @Test
    void testExportReturnsArchiveOfBothBuckets() throws Exception {
        BlobStorage storage = dial.getStorage();
        storage.store("public/files/user1/a.txt", "text/plain", null, Map.of("k", "v"), "alpha".getBytes(StandardCharsets.UTF_8));
        storage.store("platform/models/m1", "application/json", null, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
        storage.store("public/encryption_keys/key1", "application/json", null, Map.of(), "secret".getBytes(StandardCharsets.UTF_8));
        storage.store("platform/credentials/cred1", "application/json", null, Map.of(), "secret".getBytes(StandardCharsets.UTF_8));

        Download download = export("admin");

        assertEquals(200, download.status());
        assertEquals("application/zip", download.contentType());
        assertTrue(download.contentDisposition().matches("attachment; filename=\"dial-export-\\d{8}T\\d{6}Z\\.zip\""), download.contentDisposition());

        Map<String, byte[]> entries = unzip(download.body());
        assertTrue(entries.containsKey(".dial-export/manifest.json"));
        assertArrayEquals("alpha".getBytes(StandardCharsets.UTF_8), entries.get("public/files/user1/a.txt"));
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), entries.get("platform/models/m1"));
        String metadata = new String(entries.get(".dial-export/metadata/public/files/user1/a.txt"), StandardCharsets.UTF_8);
        assertTrue(metadata.contains("text/plain"), metadata);
        assertTrue(metadata.contains("\"k\""), metadata);
        assertFalse(entries.keySet().stream().anyMatch(name -> name.contains("encryption_keys") || name.contains("credentials")), entries.keySet().toString());
    }

    @Test
    void testExportIsRejectedWhileBucketIsLocked() throws Exception {
        LockService.Lock lock = lockService().tryLockBuckets(MergedConfigStore.ADMIN_BUCKET_LOCATIONS);
        assertNotNull(lock);

        Download rejected;
        try {
            rejected = export("admin");
        } finally {
            lock.close();
        }

        assertEquals(409, rejected.status());
        assertNull(rejected.contentDisposition());
        assertEquals(200, export("admin").status());
    }

    @Test
    void testClientDisconnectReleasesTheLocks() throws Exception {
        storeLargeBlobs();

        Stream stream = connect();
        assertEquals(200, stream.response().statusCode());

        stream.request().connection().close();

        awaitLocksReleased();
        assertEquals(200, export("admin").status());
    }

    @Test
    void testStalledClientFailsTheExportInsteadOfEndingIt() throws Exception {
        storeLargeBlobs();

        Stream stream = connect();
        assertEquals(200, stream.response().statusCode());

        awaitLocksReleased();

        stream.response().resume();
        assertThrows(ExecutionException.class, () -> await(stream.body()));
    }

    private LockService lockService() {
        return dial.getProxy().getLockService();
    }

    private void awaitLocksReleased() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            LockService.Lock lock = lockService().tryLockBuckets(MergedConfigStore.ADMIN_BUCKET_LOCATIONS);
            if (lock != null) {
                lock.close();
                return;
            }
            assertTrue(System.nanoTime() < deadline, "the export should release the locks");
            Thread.sleep(100);
        }
    }

    private void storeLargeBlobs() {
        Random random = new Random(1);
        for (int i = 0; i < LARGE_BLOBS; i++) {
            byte[] data = new byte[LARGE_BLOB_SIZE];
            random.nextBytes(data);
            dial.getStorage().store("public/files/user1/large-" + i, "application/octet-stream", null, Map.of(), data);
        }
    }

    private Download export(String authorization) throws Exception {
        HttpUriRequest request = new HttpUriRequestBase("POST", URI.create("http://127.0.0.1:" + serverPort + "/v1/admin/export"));
        request.setHeader("authorization", authorization);
        return client.execute(request, response -> new Download(
                response.getCode(),
                response.containsHeader("content-type") ? response.getFirstHeader("content-type").getValue() : null,
                response.containsHeader("content-disposition") ? response.getFirstHeader("content-disposition").getValue() : null,
                response.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(response.getEntity())));
    }

    /**
     * The body is subscribed to on the event loop together with the pause, otherwise the first chunks are dropped.
     */
    private Stream connect() throws Exception {
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI("http://127.0.0.1:" + serverPort + "/v1/admin/export")
                .setMethod(HttpMethod.POST)
                .putHeader("authorization", "admin");

        return await(dial.getClient().request(options).compose(request -> request.send().map(response -> {
            response.pause();
            return new Stream(request, response, response.body());
        })));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private record Download(int status, String contentType, String contentDisposition, byte[] body) {
    }

    private record Stream(HttpClientRequest request, HttpClientResponse response, Future<Buffer> body) {
    }
}

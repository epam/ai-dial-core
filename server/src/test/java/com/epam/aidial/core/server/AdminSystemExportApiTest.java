package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.service.AdminSystemExportService;
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
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * Integration tests for {@code POST /v1/admin/system-export}. The stall timeout is short, so that a client
 * that stops reading fails the export within the test. A KMS provider is configured, so that encryption keys are
 * exported; the AWS client is created lazily and no test asks it to encrypt or decrypt, so no credentials are needed.
 */
public class AdminSystemExportApiTest extends ResourceBaseTest {

    private static final DateTimeFormatter FILE_NAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String WRAPPED_KEY = "w".repeat(60);
    private static final String PLAIN_KEY = "p".repeat(32);
    private static final int LARGE_BLOBS = 32;
    private static final int LARGE_BLOB_SIZE = 2 * 1024 * 1024;

    @Override
    protected JsonObject additionalSettingsOverrides() {
        JsonObject kms = new JsonObject().put("provider", "aws").put("keyId", "test-key").put("region", "us-east-1");
        return new JsonObject()
                .put("adminSystemExport", new JsonObject().put("stallTimeout", 2000))
                .put("toolsets", new JsonObject().put("security", new JsonObject().put("kms", kms)));
    }

    @Test
    void testExportIsForbiddenForNonAdmin() {
        verify(send(HttpMethod.POST, "/v1/admin/system-export", null, "", "authorization", "user"), 403);
        verify(send(HttpMethod.POST, "/v1/admin/system-export", null, "", "Api-key", "proxyKey1"), 403);
    }

    @Test
    void testExportReturnsArchiveOfBothBuckets() throws Exception {
        BlobStorage storage = dial.getStorage();
        storage.store("public/files/user1/a.txt", "text/plain", null, Map.of("k", "v"), "alpha".getBytes(StandardCharsets.UTF_8));
        storage.store("platform/models/m1", "application/json", null, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
        storage.store("public/encryption_keys/key1", "application/json", null, Map.of(), WRAPPED_KEY.getBytes(StandardCharsets.UTF_8));
        storage.store("platform/credentials/cred1", "application/json", null, Map.of(), "secret".getBytes(StandardCharsets.UTF_8));

        Download download = export("admin");

        assertEquals(200, download.status());
        assertEquals("application/zip", download.contentType());

        Map<String, byte[]> entries = unzip(download.body());
        assertTrue(entries.containsKey(".dial-system-export/manifest.json"));
        String manifest = new String(entries.get(".dial-system-export/manifest.json"), StandardCharsets.UTF_8);
        Instant createdAt = Instant.parse(new JsonObject(manifest).getString("createdAt"));
        assertEquals("attachment; filename=\"dial-system-export-" + FILE_NAME_TIMESTAMP.format(createdAt) + ".zip\"", download.contentDisposition());
        assertArrayEquals("alpha".getBytes(StandardCharsets.UTF_8), entries.get("public/files/user1/a.txt"));
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), entries.get("platform/models/m1"));
        String metadata = new String(entries.get(".dial-system-export/metadata/public/files/user1/a.txt"), StandardCharsets.UTF_8);
        assertTrue(metadata.contains("text/plain"), metadata);
        assertTrue(metadata.contains("\"k\""), metadata);
        assertTrue(entries.containsKey("public/encryption_keys/key1"));
        assertFalse(entries.keySet().stream().anyMatch(name -> name.contains("credentials")), entries.keySet().toString());
    }

    @Test
    void testEncryptionKeysOfTheSelectedBucketsAreExported() throws Exception {
        BlobStorage storage = dial.getStorage();
        storage.store("public/encryption_keys/cek", "application/octet-stream", null, Map.of(), WRAPPED_KEY.getBytes(StandardCharsets.UTF_8));
        storage.store("platform/encryption_keys/cek", "application/octet-stream", null, Map.of(), WRAPPED_KEY.getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of("public/encryption_keys/cek", "platform/encryption_keys/cek"), contentEntries(export("admin", null)));
        assertEquals(List.of("platform/encryption_keys/cek"), contentEntries(export("admin", "scope=platform")));
    }

    @Test
    void testScopeSelectsTheBuckets() throws Exception {
        BlobStorage storage = dial.getStorage();
        storage.store("public/files/user1/a.txt", "text/plain", null, Map.of(), "alpha".getBytes(StandardCharsets.UTF_8));
        storage.store("platform/models/m1", "application/json", null, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of("platform/models/m1"), contentEntries(export("admin", "scope=platform")));
        assertEquals(List.of("public/files/user1/a.txt"), contentEntries(export("admin", "scope=PUBLIC")));
        assertEquals(List.of("public/files/user1/a.txt"), contentEntries(export("admin", "scope=%20PUBLIC%20,%20public")));
        assertEquals(List.of("public/files/user1/a.txt", "platform/models/m1"), contentEntries(export("admin", "scope=platform,public")));
        assertEquals(List.of("public/files/user1/a.txt", "platform/models/m1"), contentEntries(export("admin", "scope=platform&scope=public")));
        assertEquals(List.of("platform/models/m1"), contentEntries(export("admin", "scope=platform&scope=PLATFORM")));
        assertEquals(List.of("public/files/user1/a.txt", "platform/models/m1"), contentEntries(export("admin", null)));

        Download platformOnly = export("admin", "scope=platform");
        assertEquals(200, platformOnly.status());
        assertTrue(unzip(platformOnly.body()).containsKey(".dial-system-export/manifest.json"));
    }

    @Test
    void testInvalidScopeIsRejected() throws Exception {
        for (String query : List.of("scope=org", "scope=all", "scope=", "scope=public,", "scope=,platform", "scope=public&scope=", "scope=public&scope=org")) {
            Download rejected = export("admin", query);

            assertEquals(400, rejected.status(), query);
            assertNull(rejected.contentDisposition());
        }

        assertEquals(200, export("admin").status(), "a rejected scope must not leave a lock behind");
        assertEquals(403, export("user", "scope=org").status());
    }

    @Test
    void testExportOfPublicBucketIgnoresTheLockOfPlatformBucket() throws Exception {
        dial.getStorage().store("public/files/user1/a.txt", "text/plain", null, Map.of(), "alpha".getBytes(StandardCharsets.UTF_8));
        LockService.Lock lock = lockService().tryLockBuckets(List.of("platform/"));
        assertNotNull(lock);

        Download scoped;
        Download unscoped;
        try {
            scoped = export("admin", "scope=public");
            unscoped = export("admin");
        } finally {
            lock.close();
        }

        assertEquals(200, scoped.status());
        assertEquals(409, unscoped.status());
    }

    @Test
    void testEncryptionKeyThatCannotBeWrappedIsNotExported() throws Exception {
        BlobStorage storage = dial.getStorage();
        storage.store("public/encryption_keys/cek", "application/octet-stream", null, Map.of(), WRAPPED_KEY.getBytes(StandardCharsets.UTF_8));
        storage.store("platform/encryption_keys/cek", "application/octet-stream", null, Map.of(), PLAIN_KEY.getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of("public/encryption_keys/cek"), contentEntries(export("admin", null)));
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

    @Test
    void testExportRunningLongerThanTheLockPeriodFailsInsteadOfEnding() throws Exception {
        storeLargeBlobs();
        Logger logger = (Logger) LoggerFactory.getLogger(AdminSystemExportService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Stream stream = connect();
            assertEquals(200, stream.response().statusCode());

            time = LockService.LOCK_PERIOD.plusSeconds(1).toMillis();
            stream.response().resume();

            assertThrows(ExecutionException.class, () -> await(stream.body()));
            awaitLocksReleased();
            ILoggingEvent failure = appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).findFirst().orElseThrow();
            String cause = failure.getThrowableProxy().getCause().getMessage();
            assertTrue(cause.contains("lock period"), cause);
        } finally {
            logger.detachAppender(appender);
        }
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

    private static List<String> contentEntries(Download download) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(download.body()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.getName().startsWith(".dial-system-export/")) {
                    names.add(entry.getName());
                }
            }
        }
        return names;
    }

    private Download export(String authorization) throws Exception {
        return export(authorization, null);
    }

    private Download export(String authorization, String query) throws Exception {
        String url = "http://127.0.0.1:" + serverPort + "/v1/admin/system-export" + (query == null ? "" : "?" + query);
        HttpUriRequest request = new HttpUriRequestBase("POST", URI.create(url));
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
                .setAbsoluteURI("http://127.0.0.1:" + serverPort + "/v1/admin/system-export")
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

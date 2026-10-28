package com.epam.aidial.core.server;

import com.epam.aidial.core.storage.blobstore.BlobStorage;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@code POST /v1/admin/system-import}. {@code stallTimeout} and {@code progressInterval}
 * are short, so a stalled client and the progress lines can be exercised within the test timeout.
 *
 * <p>Service-level coverage (archive validation, the replace phase, the guard) lives in
 * {@link com.epam.aidial.core.server.service.AdminSystemImportServiceTest}; this class covers only what is
 * observable at the HTTP boundary.
 */
public class AdminSystemImportApiTest extends ResourceBaseTest {

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("adminSystemImport", new JsonObject()
                .put("stallTimeout", 1000)
                .put("progressInterval", 100));
    }

    @Test
    void testImportIsForbiddenForNonAdmin() {
        verify(send(HttpMethod.POST, "/v1/admin/system-import", null, "", "authorization", "user"), 403);
        verify(send(HttpMethod.POST, "/v1/admin/system-import", null, "", "Api-key", "proxyKey1"), 403);
    }

    @Test
    void testNotAnArchiveIsRejected() throws Exception {
        ImportResponse response = postImport("this is not a zip archive".getBytes(StandardCharsets.UTF_8), "admin");

        assertEquals(400, response.status());
        assertTrue(response.lines().isEmpty(), "a rejection before the 200 head carries a plain-text body, not ndjson");
    }

    /**
     * Staging paths embed a {@code HH:mm:ss} timestamp (as {@code .dial-tmp} already does), which the real
     * filesystem blob storage provider cannot write on Windows (colons are not valid in a Windows path). This
     * test therefore fails on a native Windows checkout and passes in CI (Linux); the same stage/replace logic
     * is exercised without that constraint, via an in-memory fake, in {@code AdminSystemImportServiceTest}.
     */
    @Test
    void testSuccessRoundTripViaExportThenImport() throws Exception {
        BlobStorage storage = dial.getStorage();
        storage.store("public/files/user1/a.txt", "text/plain", null, Map.of("k", "v"), "alpha".getBytes(StandardCharsets.UTF_8));
        storage.store("platform/models/m1", "application/json", null, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));

        byte[] archive = exportArchive();

        // a write made after the archive was taken must not survive the import
        storage.store("platform/models/stale", "application/json", null, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));

        ImportResponse response = postImport(archive, "admin");

        assertEquals(200, response.status());
        assertTrue(response.lines().size() >= 2, "expected at least a progress line and the final status line");
        JsonObject last = response.lines().get(response.lines().size() - 1);
        assertEquals("success", last.getString("status"));
        assertEquals(2, last.getInteger("importedBlobs"));

        assertFalse(storage.exists("platform/models/stale"), "a blob written after the archive was taken, and absent from it, must be removed");
        assertArrayEquals("alpha".getBytes(StandardCharsets.UTF_8), readBlob(storage, "public/files/user1/a.txt"));
        assertTrue(storage.exists("platform/models/m1"));
    }

    @Test
    void testStalledUploadTimesOut() throws Exception {
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI("http://127.0.0.1:" + serverPort + "/v1/admin/system-import")
                .setMethod(HttpMethod.POST)
                .putHeader("authorization", "admin");

        HttpClientRequest request = await(dial.getClient().request(options));
        request.setChunked(true);
        await(request.write(Buffer.buffer(new byte[] {'P', 'K', 3, 4})));
        Future<HttpClientResponse> responseFuture = request.response();

        HttpClientResponse response = await(responseFuture);
        assertEquals(408, response.statusCode());

        request.reset();
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }

    private static byte[] readBlob(BlobStorage storage, String path) throws Exception {
        try (var in = storage.load(path).getPayload().openStream()) {
            return in.readAllBytes();
        }
    }

    private byte[] exportArchive() throws Exception {
        HttpUriRequest request = new HttpUriRequestBase("POST", URI.create("http://127.0.0.1:" + serverPort + "/v1/admin/system-export"));
        request.setHeader("authorization", "admin");
        return client.execute(request, response -> {
            assertEquals(200, response.getCode());
            return EntityUtils.toByteArray(response.getEntity());
        });
    }

    private ImportResponse postImport(byte[] archive, String authorization) throws Exception {
        HttpUriRequest request = new HttpUriRequestBase("POST", URI.create("http://127.0.0.1:" + serverPort + "/v1/admin/system-import"));
        request.setHeader("authorization", authorization);
        request.setEntity(new ByteArrayEntity(archive, ContentType.APPLICATION_OCTET_STREAM));
        return client.execute(request, response -> {
            int status = response.getCode();
            byte[] raw = response.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(response.getEntity());
            List<JsonObject> lines = new ArrayList<>();
            // only a 200 carries application/x-ndjson; any other status carries a plain-text error body
            if (status == 200 && raw.length > 0) {
                for (String line : new String(raw, StandardCharsets.UTF_8).split("\n")) {
                    if (!line.isBlank()) {
                        lines.add(new JsonObject(line));
                    }
                }
            }
            return new ImportResponse(status, lines);
        });
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private record ImportResponse(int status, List<JsonObject> lines) {
    }
}

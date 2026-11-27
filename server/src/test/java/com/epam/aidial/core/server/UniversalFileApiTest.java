package com.epam.aidial.core.server;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.InvitationLink;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniversalFileApiTest extends ResourceBaseTest {

    private static final String MANIFEST = """
            ---
            name: Universal skill
            description: A skill for file API tests
            ---
            # Universal skill
            """;
    private static final String MIME_TYPE = "text/custom";
    private static final int MAX_FILES = 110;
    private static final int MAX_FILE_BYTES = 8192;
    private static final int MAX_TOTAL_BYTES = 16384;

    private BlobStorage originalStorage;
    private BlobStorage measurementStorage;
    private BlobStore measurementOriginalBackend;
    private BlobStoreContext measurementBackendContext;

    @BeforeEach
    void installFilesystemRetries(TestInfo info) {
        if (File.separatorChar != '\\') {
            return;
        }
        if (info.getTestMethod().orElseThrow().getName().equals("measureV1AndV2FileLatency")) {
            installMeasurementBackend();
            return;
        }
        originalStorage = dial.getProxy().getStorage();
        BlobStorage storage = Mockito.spy(originalStorage);
        Mockito.doAnswer(invocation -> FileUtil.retryFilesystemCall(() -> callRealStorageMethod(invocation))).when(storage)
                .store(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(byte[].class));
        Mockito.doAnswer(invocation -> FileUtil.retryFilesystemCall(() -> callRealStorageMethod(invocation))).when(storage)
                .copy(Mockito.anyString(), Mockito.anyString(), Mockito.any());
        replaceStorage(storage);
    }

    @AfterEach
    @SneakyThrows
    void restoreStorage() {
        try {
            if (measurementBackendContext != null) {
                try {
                    resolveBlobBackendField().set(measurementStorage, measurementOriginalBackend);
                } finally {
                    measurementBackendContext.close();
                }
            }
        } finally {
            if (originalStorage != null) {
                replaceStorage(originalStorage);
            }
        }
    }

    @SneakyThrows
    private void installMeasurementBackend() {
        measurementStorage = dial.getProxy().getStorage();
        measurementOriginalBackend = (BlobStore) resolveBlobBackendField().get(measurementStorage);
        measurementBackendContext = ContextBuilder.newBuilder("transient").buildView(BlobStoreContext.class);
        measurementBackendContext.getBlobStore().createContainerInLocation(null, "test");
        resolveBlobBackendField().set(measurementStorage, measurementBackendContext.getBlobStore());
    }

    private static Field resolveBlobBackendField() throws NoSuchFieldException {
        Field field = BlobStorage.class.getDeclaredField("blobStore");
        field.setAccessible(true);
        return field;
    }

    private void replaceStorage(BlobStorage storage) {
        replaceStorageField(dial.getProxy(), Proxy.class, "storage", storage);
        replaceStorageField(dial.getProxy().getResourceService(), ResourceService.class, "blobStore", storage);
        replaceStorageField(dial.getProxy().getComplexResourceService(), ComplexResourceService.class, "blobStorage", storage);
    }

    @SneakyThrows
    private static void replaceStorageField(Object owner, Class<?> type, String name, BlobStorage storage) {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, storage);
    }

    @SneakyThrows
    private static Object callRealStorageMethod(InvocationOnMock invocation) {
        return invocation.callRealMethod();
    }

    @Override
    protected JsonObject additionalSettingsOverrides() {
        JsonObject limits = new JsonObject().put("maxFiles", MAX_FILES)
                .put("maxFileSizeBytes", MAX_FILE_BYTES).put("maxTotalBytes", MAX_TOTAL_BYTES);
        return new JsonObject().put("complexResource", new JsonObject()
                .put("types", new JsonObject().put("skills", limits.copy()).put("projects", limits.copy())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testStoredUrlAndFirstDelimiter(String group) {
        String root = rootUrl(group, "team/resource");
        createResource(root, Map.of("refs/files/a.md", "nested body", "refs/other.md", "other"));
        String url = root + "/files/refs/files/a.md";
        verify(fileRequest(HttpMethod.GET, url), 200, "nested body");
        Response put = putFile(url, "replacement", MIME_TYPE);
        verify(put, 200);
        assertEquals(url, json(put).path("url").asText());
        assertEquals(root, json(put).path("resource").path("url").asText());
        verify(fileRequest(HttpMethod.GET, url), 200, "replacement");
        verify(fileRequest(HttpMethod.GET, root + "/files/refs/other.md"), 200, "other");
        verify(fileRequest(HttpMethod.DELETE, url), 200, "");
        verify(fileRequest(HttpMethod.GET, url), 404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testNonFileTargets(String group) {
        String root = rootUrl(group, "team/resource");
        createResource(root, Map.of("refs/a.md", "body"));
        for (String target : List.of(group + "/" + bucket + "/", rootUrl(group, "team/"), root)) {
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
                verify(fileRequest(method, target), 404);
            }
        }
        String before = readMarker(root);
        for (String folder : List.of(root + "/files/", root + "/files/refs/")) {
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
                verify(fileRequest(method, folder), 400);
            }
        }
        assertEquals(before, readMarker(root));
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testMetadataOnlyInsideResources(String group) {
        String root = rootUrl(group, "team/resource");
        createResource(root, Map.of("a.txt", "body"));
        for (String target : List.of(group + "/" + bucket + "/", rootUrl(group, "team/"), root)) {
            verify(fileMetadata(target, null), 404);
            verify(send(HttpMethod.GET, "/v2/metadata/" + target, null, ""), 200);
        }
        verify(fileMetadata(root + "/files/", null), 200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testMultipartMetadataAndContentType(String group) {
        String root = rootUrl(group, "mime");
        Response created = createResource(root, Map.of("a.txt", "old"));
        String before = header(created, "etag");
        Response put = putFile(root + "/files/a.txt", "new body", MIME_TYPE);
        verify(put, 200);
        assertEquals("application/json", header(put, "content-type"));
        assertFileMetadata(put, root, "a.txt", "new body", MIME_TYPE);
        assertEquals(header(put, "etag"), json(put).path("etag").asText());
        assertEquals(header(put, "x-resource-etag"), json(put).path("resource").path("etag").asText());
        String exposed = header(put, "access-control-expose-headers").toLowerCase(Locale.ROOT);
        assertTrue(exposed.contains("etag"));
        assertTrue(exposed.contains("x-resource-etag"));
        assertNotEquals(before, header(put, "x-resource-etag"));
        Response metadata = fileMetadata(root + "/files/a.txt", null);
        verify(metadata, 200);
        assertEquals(json(put), json(metadata));
        Response get = fileRequest(HttpMethod.GET, root + "/files/a.txt");
        verify(get, 200, "new body");
        assertEquals(MIME_TYPE, header(get, "content-type"));
        assertEquals(header(put, "etag"), header(get, "etag"));
        assertEquals(header(put, "x-resource-etag"), header(get, "x-resource-etag"));
        assertEquals(header(put, "x-resource-etag"), aggregateEtag(root));
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testInvalidMultipart(String group) {
        String root = rootUrl(group, "invalid-upload");
        createResource(root, Map.of("a.txt", "unchanged"));
        String url = root + "/files/a.txt";
        String before = readMarker(root);
        verify(send(HttpMethod.PUT, "/v1/" + url, null, null), 400);
        for (String contentType : List.of("text/plain", "application/json", "multipart/form-data")) {
            verify(send(HttpMethod.PUT, "/v1/" + url, null, "body", "content-type", contentType), 400);
        }
        MultipartEntityBuilder zeroParts = multipartBuilder().addTextBody("field", "not a file");
        verify(sendMultipart("/v1/" + url, zeroParts.build()), 400);
        MultipartEntityBuilder twoParts = multipartBuilder()
                .addBinaryBody("first", bytes("one"), ContentType.TEXT_PLAIN, "one.txt")
                .addBinaryBody("second", bytes("two"), ContentType.TEXT_PLAIN, "two.txt");
        verify(sendMultipart("/v1/" + url, twoParts.build()), 400);
        assertEquals(before, readMarker(root));
        verify(fileRequest(HttpMethod.GET, url), 200, "unchanged");
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testChunkedOversizeUpload(String group) {
        String root = rootUrl(group, "chunked");
        createResource(root, Map.of("a.txt", "unchanged"));
        String before = readMarker(root);
        String boundary = "universal-file-boundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n"
                + "Content-Type: text/custom\r\n\r\n" + "x".repeat(MAX_FILE_BYTES + 1) + "\r\n--" + boundary + "--\r\n";
        HttpEntity chunked = new InputStreamEntity(new ByteArrayInputStream(bytes(body)), -1,
                ContentType.parse("multipart/form-data; boundary=" + boundary));
        verify(sendMultipart("/v1/" + root + "/files/a.txt", chunked), 413);
        assertEquals(before, readMarker(root));
        verify(fileRequest(HttpMethod.GET, root + "/files/a.txt"), 200, "unchanged");
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testExistingResourceRequired(String group) {
        String root = rootUrl(group, "absent");
        assertCreationGuidance(putFile(root + "/files/a.txt", "body", MIME_TYPE), root);
        assertNull(readMarker(root));
        verify(send(HttpMethod.GET, "/v2/metadata/" + root, null, ""), 404);
        createResource(root, Map.of("a.txt", "body"));
        verify(send(HttpMethod.DELETE, "/v2/" + root, null, ""), 200);
        String deletingMarker = readMarker(root);
        assertEquals("deleting", json(deletingMarker).path("state").asText());
        assertCreationGuidance(putFile(root + "/files/a.txt", "replacement", MIME_TYPE), root);
        assertEquals(deletingMarker, readMarker(root));
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testMissingDownload(String group) {
        String root = rootUrl(group, "missing-download");
        verify(fileRequest(HttpMethod.GET, root + "/files/a.txt"), 404);
        createResource(root, Map.of("a.txt", "body"));
        verify(fileRequest(HttpMethod.GET, root + "/files/absent.txt"), 404);
        verify(send(HttpMethod.DELETE, "/v2/" + root, null, ""), 200);
        assertEquals("deleting", json(readMarker(root)).path("state").asText());
        verify(fileRequest(HttpMethod.GET, root + "/files/a.txt"), 404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    @SneakyThrows
    void testDownloadTransport(String group) {
        String root = rootUrl(group, "transport");
        String body = "compressible file body\n".repeat(100);
        createResource(root, Map.of());
        Response put = putFile(root + "/files/a.txt", body, MIME_TYPE);
        verify(put, 200);
        String plainUrl = "files/" + bucket + "/transport.txt";
        verify(putFile(plainUrl, body, MIME_TYPE), 200);
        try (CloseableHttpClient rawClient = HttpClientBuilder.create().disableContentCompression().disableAutomaticRetries().build()) {
            for (String encoding : List.of("identity", "gzip")) {
                for (String range : List.of("", "bytes=0-3")) {
                    BinaryResponse plain = downloadRaw(rawClient, "/v1/" + plainUrl, encoding, range);
                    BinaryResponse complex = downloadRaw(rawClient, "/v1/" + root + "/files/a.txt", encoding, range);
                    assertEquals(200, plain.status());
                    assertEquals(plain.status(), complex.status());
                    assertArrayEquals(bytes(body), decodeBody(complex));
                    assertArrayEquals(decodeBody(plain), decodeBody(complex));
                    assertEquals(plain.headers().get("content-type"), complex.headers().get("content-type"));
                    assertEquals(plain.headers().get("content-encoding"), complex.headers().get("content-encoding"));
                    assertEquals(plain.headers().get("content-length"), complex.headers().get("content-length"));
                    assertNull(complex.headers().get("content-range"));
                    assertEquals(header(put, "etag"), complex.headers().get("etag"));
                    assertEquals(header(put, "x-resource-etag"), complex.headers().get("x-resource-etag"));
                    Set<String> exposed = new HashSet<>(Arrays.asList(complex.headers().get("access-control-expose-headers")
                            .toLowerCase(Locale.ROOT).split(",\\s*")));
                    assertTrue(exposed.contains("etag"));
                    assertTrue(exposed.contains("x-resource-etag"));
                    if (encoding.equals("identity")) {
                        assertEquals(Integer.toString(bytes(body).length), complex.headers().get("content-length"));
                    } else {
                        assertEquals("gzip", complex.headers().get("content-encoding"));
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testFileConditionalRequests(String group) {
        String root = rootUrl(group, "conditions");
        createResource(root, Map.of("a.txt", "original"));
        List<Condition> conditions = List.of(
                new Condition(null, null, 200, 404, 200, 200, 200, 404),
                new Condition("if-match", "$etag", 200, 404, 200, 412, 200, 412),
                new Condition("if-match", "\"wrong\"", 412, 404, 412, 412, 412, 412),
                new Condition("if-match", "\"wrong\",$etag", 200, 404, 200, 412, 200, 412),
                new Condition("if-match", "\"wrong\",\"also-wrong\"", 412, 404, 412, 412, 412, 412),
                new Condition("if-match", "*", 200, 404, 200, 412, 200, 412),
                new Condition("if-none-match", "$etag", 304, 404, 412, 200, 412, 404),
                new Condition("if-none-match", "\"wrong\"", 200, 404, 200, 200, 200, 404),
                new Condition("if-none-match", "\"wrong\",$etag", 304, 404, 412, 200, 412, 404),
                new Condition("if-none-match", "\"wrong\",\"also-wrong\"", 200, 404, 200, 200, 200, 404),
                new Condition("if-none-match", "*", 304, 404, 412, 200, 412, 404));
        int index = 0;
        for (Condition condition : conditions) {
            String url = root + "/files/a.txt";
            Response seed = putFile(url, "original", MIME_TYPE);
            verify(seed, 200);
            String etag = header(seed, "etag");
            String[] headers = condition.name() == null ? new String[0]
                    : new String[]{condition.name(), condition.value().replace("$etag", etag)};
            String before = readMarker(root);
            Response get = fileRequest(HttpMethod.GET, url, headers);
            verify(get, condition.getExisting());
            if (get.status() == 304) {
                assertEquals(etag, header(get, "etag"));
                assertTrue(get.body() == null || get.body().isEmpty());
            }
            assertEquals(before, readMarker(root));
            String absent = root + "/files/absent-" + index++ + ".txt";
            Response plainAbsent = fileRequest(HttpMethod.GET, "files/" + bucket + "/conditions/absent-" + index + ".txt", headers);
            verify(plainAbsent, condition.getAbsent());
            Response complexAbsent = fileRequest(HttpMethod.GET, absent, headers);
            verify(complexAbsent, plainAbsent.status());
            Response put = putFile(url, "replacement", MIME_TYPE, headers);
            verify(put, condition.putExisting());
            if (put.status() == 412) {
                assertEquals(before, readMarker(root));
                verify(fileRequest(HttpMethod.GET, url), 200, "original");
            }
            verify(putFile(absent, "created", MIME_TYPE, headers), condition.putAbsent());
            verify(putFile(url, "original", MIME_TYPE), 200);
            before = readMarker(root);
            Response delete = fileRequest(HttpMethod.DELETE, url, headers);
            verify(delete, condition.deleteExisting());
            if (delete.status() == 412) {
                assertEquals(before, readMarker(root));
                verify(fileRequest(HttpMethod.GET, url), 200, "original");
            } else {
                verify(fileRequest(HttpMethod.GET, url), 404);
            }
            verify(fileRequest(HttpMethod.DELETE, root + "/files/never-" + index + ".txt", headers), condition.deleteAbsent());
        }
        Response seed = putFile(root + "/files/a.txt", "original", MIME_TYPE);
        verify(fileRequest(HttpMethod.GET, root + "/files/a.txt", "if-match", "\"wrong\"",
                "if-none-match", header(seed, "etag")), 412);
        // Aggregate ETags are deliberately not accepted as file preconditions.
        verify(putFile(root + "/files/a.txt", "replacement", MIME_TYPE, "if-match", aggregateEtag(root)), 412);
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    @SneakyThrows
    void testDifferentFileWriters(String group) {
        String root = rootUrl(group, "writers");
        createResource(root, Map.of("a.txt", "a", "b.txt", "b"));
        String firstFileEtag = header(fileRequest(HttpMethod.GET, root + "/files/a.txt"), "etag");
        String secondFileEtag = header(fileRequest(HttpMethod.GET, root + "/files/b.txt"), "etag");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Response> a = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return putFile(root + "/files/a.txt", "new a", MIME_TYPE, "if-match", firstFileEtag);
            });
            Future<Response> b = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return putFile(root + "/files/b.txt", "new b", MIME_TYPE, "if-match", secondFileEtag);
            });
            start.countDown();
            for (Response response : List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))) {
                verify(response, 200);
                assertEquals(header(response, "etag"), json(response).path("etag").asText());
                assertEquals(header(response, "x-resource-etag"), json(response).path("resource").path("etag").asText());
                assertEquals(root, json(response).path("resource").path("url").asText());
            }
        }
        verify(fileRequest(HttpMethod.GET, root + "/files/a.txt"), 200, "new a");
        verify(fileRequest(HttpMethod.GET, root + "/files/b.txt"), 200, "new b");
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testStaleSameFileWriter(String group) {
        String root = rootUrl(group, "stale-writer");
        createResource(root, Map.of("a.txt", "original"));
        String etag = header(fileRequest(HttpMethod.GET, root + "/files/a.txt"), "etag");
        verify(putFile(root + "/files/a.txt", "first writer", MIME_TYPE, "if-match", etag), 200);
        String before = readMarker(root);
        verify(putFile(root + "/files/a.txt", "stale writer", MIME_TYPE, "if-match", etag), 412);
        verify(fileRequest(HttpMethod.DELETE, root + "/files/a.txt", "if-match", etag), 412);
        assertEquals(before, readMarker(root));
        verify(fileRequest(HttpMethod.GET, root + "/files/a.txt"), 200, "first writer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testDeleteFileAndLastProjectFile(String group) {
        String root = rootUrl(group, "delete-file");
        Response created = createResource(root, Map.of("a.txt", "body"));
        Response delete = fileRequest(HttpMethod.DELETE, root + "/files/a.txt");
        verify(delete, 200, "");
        assertNull(header(delete, "etag"));
        assertNotNull(header(delete, "x-resource-etag"));
        assertNotEquals(header(created, "etag"), header(delete, "x-resource-etag"));
        assertEquals(header(delete, "x-resource-etag"), aggregateEtag(root));
        verify(fileRequest(HttpMethod.GET, root + "/files/a.txt"), 404);
        verify(fileRequest(HttpMethod.DELETE, root + "/files/a.txt"), 404);
        Response listing = fileMetadata(root + "/files/", null);
        verify(listing, 200);
        assertEquals(group.equals("projects") ? 0 : 1, json(listing).path("items").size());
        assertEquals("active", json(readMarker(root)).path("state").asText());
    }

    @Test
    void testManifestValidation() {
        String root = rootUrl("skills", "manifest");
        createResource(root, Map.of("a.txt", "body"));
        String before = readMarker(root);
        verify(fileRequest(HttpMethod.DELETE, root + "/files/SKILL.md"), 400);
        for (String invalid : List.of("# no frontmatter", "---\nname: Missing description\n---\n", "---\n: [bad yaml\n---\n")) {
            verify(putFile(root + "/files/SKILL.md", invalid, "text/markdown"), 400);
            assertEquals(before, readMarker(root));
            verify(fileRequest(HttpMethod.GET, root + "/files/SKILL.md"), 200, MANIFEST);
            verify(fileRequest(HttpMethod.GET, root + "/files/a.txt"), 200, "body");
        }
        String replacement = MANIFEST.replace("Universal skill", "Renamed skill");
        verify(putFile(root + "/files/SKILL.md", replacement, "text/markdown"), 200);
        verify(fileRequest(HttpMethod.GET, root + "/files/SKILL.md"), 200, replacement);
        assertNotEquals(before, readMarker(root));
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testFileLimits(String group) {
        String countRoot = rootUrl(group, "count-limit");
        Map<String, String> files = new LinkedHashMap<>();
        int ordinaryCount = MAX_FILES - (group.equals("skills") ? 1 : 0);
        for (int i = 0; i < ordinaryCount; i++) {
            files.put("file-" + i + ".txt", "x");
        }
        createResource(countRoot, files);
        String before = readMarker(countRoot);
        verify(putFile(countRoot + "/files/excess.txt", "x", MIME_TYPE), 400);
        assertEquals(before, readMarker(countRoot));
        verify(fileRequest(HttpMethod.GET, countRoot + "/files/excess.txt"), 404);
        verify(fileRequest(HttpMethod.GET, countRoot + "/files/file-0.txt"), 200, "x");
        verify(putFile(countRoot + "/files/file-0.txt", "replacement", MIME_TYPE), 200);

        String sizeRoot = rootUrl(group, "size-limit");
        int manifestBytes = group.equals("skills") ? bytes(MANIFEST).length : 0;
        createResource(sizeRoot, Map.of("a.txt", "a".repeat(MAX_FILE_BYTES),
                "b.txt", "b".repeat(MAX_TOTAL_BYTES - MAX_FILE_BYTES - manifestBytes)));
        before = readMarker(sizeRoot);
        verify(putFile(sizeRoot + "/files/excess.txt", "x", MIME_TYPE), 413);
        assertEquals(before, readMarker(sizeRoot));
        verify(fileRequest(HttpMethod.GET, sizeRoot + "/files/excess.txt"), 404);
        verify(putFile(sizeRoot + "/files/a.txt", "x".repeat(MAX_FILE_BYTES + 1), MIME_TYPE), 413);
        assertEquals(before, readMarker(sizeRoot));
        verify(fileRequest(HttpMethod.GET, sizeRoot + "/files/a.txt"), 200, "a".repeat(MAX_FILE_BYTES));
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testLogicalEvents(String group) {
        String root = rootUrl(group, "events");
        createResource(root, Map.of("a.txt", "original"));
        try (EventStream events = subscribe(new JsonObject().put("resources", List.of(Map.of("url", root))).encode())) {
            verify(putFile(root + "/files/refs/a.txt", "created", MIME_TYPE), 200);
            assertEvent(events, root, "UPDATE");
            assertEvent(events, root + "/files/refs/a.txt", "CREATE");
            verify(putFile(root + "/files/refs/a.txt", "updated", MIME_TYPE), 200);
            assertEvent(events, root, "UPDATE");
            assertEvent(events, root + "/files/refs/a.txt", "UPDATE");
            verify(fileRequest(HttpMethod.DELETE, root + "/files/refs/a.txt"), 200);
            assertEvent(events, root, "UPDATE");
            assertEvent(events, root + "/files/refs/a.txt", "DELETE");
            assertTrue(events.events.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testFileMetadata(String group) {
        String root = rootUrl(group, "team/metadata");
        createResource(root, Map.of());
        Response put = putFile(root + "/files/refs/a.md", "metadata body", MIME_TYPE);
        verify(put, 200);
        Response metadata = fileMetadata(root + "/files/refs/a.md", null);
        verify(metadata, 200);
        assertFileMetadata(metadata, root, "refs/a.md", "metadata body", MIME_TYPE);
        assertEquals(json(put), json(metadata));
        assertEquals(header(put, "x-resource-etag"), aggregateEtag(root));
        Response ignoredConditions = fileMetadata(root + "/files/refs/a.md", null,
                "if-match", "\"wrong\"", "if-none-match", header(put, "etag"));
        verify(ignoredConditions, 200);
        assertEquals(json(metadata), json(ignoredConditions));
    }

    @Test
    void testPlainFileMetadataHasNoResource() {
        String url = "files/" + bucket + "/plain.txt";
        Response put = putFile(url, "plain body", MIME_TYPE);
        verify(put, 200);
        JsonNode uploaded = json(put);
        assertFalse(uploaded.has("resource"));
        assertEquals("ITEM", uploaded.path("nodeType").asText());
        assertEquals("FILE", uploaded.path("resourceType").asText());
        assertEquals(url, uploaded.path("url").asText());
        assertEquals(10, uploaded.path("contentLength").asInt());
        assertEquals(MIME_TYPE, uploaded.path("contentType").asText());
        Response metadata = fileMetadata(url, null);
        verify(metadata, 200);
        assertFalse(json(metadata).has("resource"));
        assertEquals(uploaded, json(metadata));
        assertEquals(uploaded.path("etag").asText(), header(fileRequest(HttpMethod.GET, url), "etag"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testMissingMetadata(String group) {
        String root = rootUrl(group, "missing-metadata");
        for (String target : List.of("a.txt", "", "refs/")) {
            verify(fileMetadata(root + "/files/" + target, null), 404);
        }
        createResource(root, Map.of("a.txt", "body"));
        verify(fileMetadata(root + "/files/missing.txt", null), 404);
        verify(fileMetadata(root + "/files/missing-folder/", null), 404);
        verify(send(HttpMethod.DELETE, "/v2/" + root, null, ""), 200);
        for (String target : List.of("a.txt", "", "refs/")) {
            verify(fileMetadata(root + "/files/" + target, null), 404);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testFolderMetadataUrls(String group) {
        String root = rootUrl(group, "team/folders");
        createResource(root, Map.of("top.txt", "top", "refs/a.md", "a", "refs/deep/b.md", "b"));
        for (boolean recursive : List.of(false, true)) {
            Response top = fileMetadata(root + "/files/", "recursive=" + recursive);
            verify(top, 200);
            assertFolder(json(top), root + "/files/", "files", "team/folders");
            Response refs = fileMetadata(root + "/files/refs/", "recursive=" + recursive);
            verify(refs, 200);
            assertFolder(json(refs), root + "/files/refs/", "refs", "team/folders/files");
            assertLogicalMetadata(json(top), root);
            assertLogicalMetadata(json(refs), root);
            if (recursive) {
                assertTrue(itemUrls(json(refs)).contains(root + "/files/refs/deep/b.md"));
                assertFalse(itemUrls(json(refs)).contains(root + "/files/top.txt"));
            } else {
                assertEquals(Set.of(root + "/files/refs/a.md", root + "/files/refs/deep/"), itemUrls(json(refs)));
            }
        }
        Response deep = fileMetadata(root + "/files/refs/deep/", null);
        verify(deep, 200);
        assertFolder(json(deep), root + "/files/refs/deep/", "deep", "team/folders/files/refs");
        assertEquals(Set.of(root + "/files/refs/deep/b.md"), itemUrls(json(deep)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testMetadataPaginationAndRecursion(String group) {
        String root = rootUrl(group, "pagination");
        createResource(root, Map.of("refs/a.txt", "a", "refs/b.txt", "b", "refs/c.txt", "c", "refs/deep/d.txt", "d"));
        String folder = root + "/files/refs/";
        Set<String> seen = new HashSet<>();
        String token = null;
        int pages = 0;
        do {
            String query = "limit=2" + (token == null ? "" : "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
            Response page = fileMetadata(folder, query);
            verify(page, 200);
            JsonNode listing = json(page);
            assertTrue(listing.path("items").size() <= 2);
            assertTrue(pages++ < 10, "Pagination did not terminate");
            for (String url : itemUrls(listing)) {
                assertTrue(seen.add(url), "Duplicate item: " + url);
            }
            if (token != null) {
                Response replay = fileMetadata(folder, query);
                verify(replay, 200);
                assertEquals(listing, json(replay));
            }
            token = listing.hasNonNull("nextToken") ? listing.get("nextToken").asText() : null;
        } while (token != null);
        assertEquals(Set.of(folder + "a.txt", folder + "b.txt", folder + "c.txt", folder + "deep/"), seen);
        assertTrue(pages > 1);
        Response firstPage = fileMetadata(folder, "limit=1");
        verify(firstPage, 200);
        assertTrue(json(firstPage).hasNonNull("nextToken"));
        String continuation = URLEncoder.encode(json(firstPage).get("nextToken").asText(), StandardCharsets.UTF_8);
        Response largerPage = fileMetadata(folder, "limit=1000&token=" + continuation);
        verify(largerPage, 200);
        Set<String> remainingUrls = new HashSet<>(seen);
        remainingUrls.removeAll(itemUrls(json(firstPage)));
        assertEquals(remainingUrls, itemUrls(json(largerPage)));
        verify(fileMetadata(folder, "limit=1&token=not-a-valid-token"), 400, "Invalid file listing token");
        verify(fileMetadata(root + "/files/refs/deep/", "limit=1&token=" + continuation), 400, "Invalid file listing token");
        verify(fileMetadata(root + "/files/", "limit=1&token=" + continuation), 400, "Invalid file listing token");
        verify(fileMetadata(folder, "recursive=true&limit=1&token=" + continuation), 400, "Invalid file listing token");
        String otherRoot = rootUrl(group, "other-pagination");
        createResource(otherRoot, Map.of("refs/a.txt", "other"));
        verify(fileMetadata(otherRoot + "/files/refs/", "limit=1&token=" + continuation), 400, "Invalid file listing token");
        Response recursive = fileMetadata(folder, "recursive=true&limit=1000");
        verify(recursive, 200);
        assertTrue(itemUrls(json(recursive)).contains(folder + "deep/d.txt"));
        assertLogicalMetadata(json(recursive), root);
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testMetadataLimits(String group) {
        String root = rootUrl(group, "listing-limits");
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < 105; i++) {
            files.put("refs/file-%03d.txt".formatted(i), "x");
        }
        createResource(root, files);
        String folder = root + "/files/refs/";
        Response defaults = fileMetadata(folder, null);
        verify(defaults, 200);
        assertEquals(100, json(defaults).path("items").size());
        assertTrue(json(defaults).hasNonNull("nextToken"));
        Response zero = fileMetadata(folder, "limit=0");
        verify(zero, 200);
        assertEquals(0, json(zero).path("items").size());
        Response maximum = fileMetadata(folder, "limit=1000");
        verify(maximum, 200);
        assertEquals(105, json(maximum).path("items").size());
        for (int limit : List.of(-1, 1001)) {
            verify(fileMetadata(folder, "limit=" + limit), 400, "Limit is out of allowed range: [0, 1000]");
        }
        verify(fileMetadata(folder, "limit=invalid"), 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testMetadataContentNegotiation(String group) {
        String root = rootUrl(group, "negotiation");
        createResource(root, Map.of("a.txt", "body"));
        for (String suffix : List.of("", "a.txt")) {
            String target = root + "/files/" + suffix;
            Response defaults = fileMetadata(target, null);
            verify(defaults, 200);
            assertEquals("application/json", header(defaults, "content-type"));
            Response accepted = fileMetadata(target, null, "accept", MetadataBase.MIME_TYPE);
            verify(accepted, 200);
            assertEquals(MetadataBase.MIME_TYPE, header(accepted, "content-type"));
            assertEquals(json(defaults), json(accepted));
            Response other = fileMetadata(target, null, "accept", "text/plain");
            verify(other, 200);
            assertEquals("application/json", header(other, "content-type"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testRootPermissionsInMetadata(String group) {
        String root = rootUrl(group, "permissions/resource");
        createResource(root, Map.of("refs/a.txt", "a", "refs/deep/b.txt", "b"));
        acceptReadShare(root);
        for (String target : List.of(root + "/files/refs/a.txt", root + "/files/", root + "/files/refs/")) {
            Response metadata = fileMetadata(target, "recursive=true&permissions=true", "api-key", "proxyKey2");
            verify(metadata, 200);
            assertPermissions(json(metadata), true);
            Response omitted = fileMetadata(target, "recursive=true", "api-key", "proxyKey2");
            verify(omitted, 200);
            assertPermissions(json(omitted), false);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testAccessBeforeExistence(String group) {
        String existing = rootUrl(group, "foreign/existing");
        createResource(existing, Map.of("a.txt", "secret"));
        for (String root : List.of(existing, rootUrl(group, "foreign/absent"))) {
            for (String suffix : List.of("a.txt", "missing.txt", "refs/", "%2Edial-resource", "refs/%76/x.txt")) {
                String target = root + "/files/" + suffix;
                for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
                    verify(fileRequest(method, target, "api-key", "proxyKey2"), 403, "You don't have an access to: " + root);
                }
                verify(fileMetadata(target, null, "api-key", "proxyKey2"), 403, "You don't have an access to: " + root);
            }
        }
        verify(fileRequest(HttpMethod.GET, existing + "/files/missing.txt"), 404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testHiddenPaths(String group) {
        String root = rootUrl(group, "hidden");
        createResource(root, Map.of("refs/a.txt", "body"));
        String before = readMarker(root);
        for (String segment : List.of(".dial-resource", ".dial-folder", "v", "%2Edial-resource", "%2edial-folder", "%76")) {
            for (String path : List.of(segment, "refs/" + segment + "/a.txt")) {
                String target = root + "/files/" + path;
                for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
                    verify(fileRequest(method, target), 404);
                }
                verify(fileMetadata(target, null), 404);
            }
            String hiddenRoot = rootUrl(group, "team/" + segment + "/resource");
            verify(fileRequest(HttpMethod.GET, hiddenRoot + "/files/a.txt"), 404);
            verify(fileMetadata(hiddenRoot + "/files/", null), 404);
        }
        for (String alias : List.of("refs%2Fa.txt", "refs%5Ca.txt", "%2E%2E/a.txt", "refs/%2E/a.txt")) {
            String target = root + "/files/" + alias;
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
                Response rejected = fileRequest(method, target);
                assertTrue(rejected.status() == 400 || rejected.status() == 404, rejected::body);
            }
            Response rejected = fileMetadata(target, null);
            assertTrue(rejected.status() == 400 || rejected.status() == 404, rejected::body);
        }
        assertEquals(before, readMarker(root));
        verify(fileRequest(HttpMethod.GET, root + "/files/refs/a.txt"), 200, "body");
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testSharedAndPublishedRootAccess(String group) {
        for (boolean folderShare : List.of(false, true)) {
            String root = rootUrl(group, "shared-" + folderShare + "/resource");
            createResource(root, Map.of("refs/a.txt", "shared body"));
            acceptReadShare(folderShare ? rootUrl(group, "shared-" + folderShare + "/") : root);
            assertReadOnlyAccess(root, "shared body", "api-key", "proxyKey2");
        }
        String source = rootUrl(group, "published");
        String target = group + "/public/universal/" + group + "/published";
        createResource(source, Map.of("refs/a.txt", "published body"));
        Response publication = operationRequest("/v1/ops/publication/create", """
                {"name":"Universal file publication","targetFolder":"public/universal/%s/",
                 "resources":[{"action":"ADD","sourceUrl":"%s","targetUrl":"%s"}],
                 "rules":[{"source":"roles","function":"TRUE"}]}
                """.formatted(group, source, target));
        verify(publication, 200);
        String publicationUrl = json(publication).path("url").asText();
        assertFalse(publicationUrl.isEmpty(), publication::body);
        verify(operationRequest("/v1/ops/publication/approve", new JsonObject().put("url", publicationUrl).encode(),
                "authorization", "admin"), 200);
        assertReadOnlyAccess(target, "published body", "api-key", "proxyKey2");
        verify(fileRequest(HttpMethod.GET, source + "/files/refs/a.txt"), 200, "published body");
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testPerRequestRootAccess(String group) {
        String root = rootUrl(group, "granted/resource");
        createResource(root, Map.of("refs/a.txt", "granted body"));
        for (String grant : List.of(root, rootUrl(group, "granted/"))) {
            ApiKeyData key = createAppKey("guest", Map.of());
            key.setPerRequestSharedResources(Map.of(grant, new PerRequestSharedData(Set.of(ResourceAccessType.READ))));
            apiKeyStore.assignPerRequestApiKey(key);
            assertReadOnlyAccess(root, "granted body", "api-key", key.getPerRequestKey());
        }
        ApiKeyData writable = createAppKey("guest", Map.of());
        writable.setPerRequestSharedResources(Map.of(root,
                new PerRequestSharedData(Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE))));
        apiKeyStore.assignPerRequestApiKey(writable);
        verify(putFile(root + "/files/refs/a.txt", "written by app", MIME_TYPE, "api-key", writable.getPerRequestKey()), 200);
        verify(fileRequest(HttpMethod.DELETE, root + "/files/refs/a.txt", "api-key", writable.getPerRequestKey()), 200);
        verify(fileRequest(HttpMethod.GET, root + "/files/refs/a.txt"), 404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills", "projects"})
    void testLegacyRouteCompatibility(String group) {
        String root = rootUrl(group, "legacy");
        Response created = createResource(root, Map.of("a.txt", "original"));
        verify(created, 200, "");
        String aggregate = header(created, "etag");
        Response get = send(HttpMethod.GET, "/v2/" + root + "/files/a.txt", null, "", "accept-encoding", "identity");
        verify(get, 200, "original");
        String fileEtag = header(get, "etag");
        assertNotNull(fileEtag);
        assertNotEquals(aggregate, fileEtag);
        assertNull(header(get, "x-resource-etag"));
        verify(send(HttpMethod.GET, "/v2/" + root + "/files/a.txt", null, "", "if-match", "\"wrong\"",
                "if-none-match", fileEtag), 200, "original");
        verify(sendMultipart("/v2/" + root + "/files/a.txt", oneFile("replacement", MIME_TYPE), "if-match", fileEtag), 412);
        Response put = sendMultipart("/v2/" + root + "/files/a.txt", oneFile("replacement", MIME_TYPE), "if-match", aggregate);
        verify(put, 200, "");
        aggregate = header(put, "etag");
        assertNotNull(aggregate);
        assertNull(header(put, "x-resource-etag"));
        assertEquals(aggregate, aggregateEtag(root));
        Response newGet = send(HttpMethod.GET, "/v2/" + root + "/files/a.txt", null, "");
        verify(newGet, 200, "replacement");
        assertEquals("text/plain", header(newGet, "content-type"));
        verify(send(HttpMethod.DELETE, "/v2/" + root + "/files/a.txt", null, "", "if-match", header(newGet, "etag")), 412);
        Response delete = send(HttpMethod.DELETE, "/v2/" + root + "/files/a.txt", null, "", "if-match", aggregate);
        verify(delete, 200, "");
        assertNotNull(header(delete, "etag"));
        assertEquals(header(delete, "etag"), aggregateEtag(root));
        assertNull(header(delete, "x-resource-etag"));
        for (Response response : List.of(created, get, put, delete)) {
            assertNull(header(response, "location"));
            assertNull(header(response, "deprecation"));
            assertNull(header(response, "sunset"));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "UNIVERSAL_FILE_API_MEASURE", matches = "true")
    void measureV1AndV2FileLatency() {
        int repetitions = 10;
        int warmups = 3;
        String body = "x".repeat(1024);
        System.out.printf(Locale.ROOT,
                "Universal file measurement: JVM=%s %s, OS=%s, backingstore=%s, wrapper=filesystem, Redis=embedded, "
                        + "local/NOT production-storage benchmark, cacheExpiration=1000ms, syncPeriod=1000ms, syncDelay=1000ms, "
                        + "warmups=%d, repetitions=%d, fileBytes=%d, MIME=text/plain, filesystemRetries=%s, no latency thresholds%n",
                System.getProperty("java.vm.name"), System.getProperty("java.version"), System.getProperty("os.name"),
                measurementBackendContext == null ? "filesystem" : "transient", warmups, repetitions, bytes(body).length, originalStorage != null);
        for (String group : List.of("skills", "projects")) {
            String root = rootUrl(group, "measurement");
            createResource(root, Map.of("SKILL.md", MANIFEST, "a.txt", body, "b.txt", body, "c.txt", body));
            System.out.printf(Locale.ROOT, "Measurement resource: group=%s, files=%d, payloadBytes=%d%n", group,
                    4, 3 * bytes(body).length + bytes(MANIFEST).length);
            for (String version : List.of("v1", "v2")) {
                String path = "/" + version + "/" + root + "/files/a.txt";
                for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT)) {
                    for (boolean cold : List.of(false, true)) {
                        for (int i = 0; i < warmups; i++) {
                            executeMeasuredRequest(method, path, body);
                        }
                        long[] elapsed = new long[repetitions];
                        for (int i = 0; i < repetitions; i++) {
                            if (cold) {
                                evictPersistedResource(root);
                            } else {
                                verify(fileRequest(HttpMethod.GET, root + "/files/a.txt"), 200, body);
                            }
                            long start = System.nanoTime();
                            executeMeasuredRequest(method, path, body);
                            elapsed[i] = System.nanoTime() - start;
                        }
                        Arrays.sort(elapsed);
                        double mean = Arrays.stream(elapsed).average().orElseThrow() / 1_000_000.0;
                        System.out.printf(Locale.ROOT,
                                "Measurement: group=%s api=%s method=%s cache=%s n=%d min=%.3fms p50=%.3fms p95=%.3fms max=%.3fms mean=%.3fms%n",
                                group, version, method, cold ? "cold" : "warm", repetitions, elapsed[0] / 1_000_000.0,
                                elapsed[repetitions / 2] / 1_000_000.0, elapsed[(int) Math.ceil(repetitions * 0.95) - 1] / 1_000_000.0,
                                elapsed[repetitions - 1] / 1_000_000.0, mean);
                    }
                }
            }
        }
    }

    private String rootUrl(String group, String path) {
        return group + "/" + bucket + "/" + path;
    }

    private Response createResource(String root, Map<String, String> files, String... headers) {
        MultipartEntityBuilder builder = multipartBuilder();
        Map<String, String> resourceFiles = new LinkedHashMap<>(files);
        if (root.startsWith("skills/")) {
            resourceFiles.putIfAbsent("SKILL.md", MANIFEST);
        }
        // Multipart resource creation rejects zero parts; use a seed file and delete it to create an empty project.
        boolean emptyProject = resourceFiles.isEmpty();
        if (emptyProject) {
            resourceFiles.put("seed.txt", "seed");
        }
        for (Map.Entry<String, String> file : resourceFiles.entrySet()) {
            builder.addBinaryBody(file.getKey(), bytes(file.getValue()), ContentType.DEFAULT_BINARY, file.getKey());
        }
        Response response = sendMultipart("/v2/" + root, builder.build(), headers);
        verify(response, 200);
        if (emptyProject) {
            verify(fileRequest(HttpMethod.DELETE, root + "/files/seed.txt", headers), 200);
        }
        return response;
    }

    private Response putFile(String url, String body, String mimeType, String... headers) {
        return sendMultipart("/v1/" + url, oneFile(body, mimeType), headers);
    }

    private static HttpEntity oneFile(String body, String mimeType) {
        return multipartBuilder().addBinaryBody("file", bytes(body), ContentType.create(mimeType), "upload.bin").build();
    }

    private static MultipartEntityBuilder multipartBuilder() {
        return MultipartEntityBuilder.create().setMode(HttpMultipartMode.LEGACY).setCharset(StandardCharsets.UTF_8);
    }

    @SneakyThrows
    private Response sendMultipart(String path, HttpEntity entity, String... headers) {
        HttpUriRequestBase request = new HttpUriRequestBase("PUT", URI.create("http://127.0.0.1:" + serverPort + path));
        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
            request.setHeader("api-key", "proxyKey1");
        }
        request.setEntity(entity);
        return client.execute(request, ResourceBaseTest::toResponse);
    }

    private Response fileRequest(HttpMethod method, String url, String... headers) {
        return send(method, "/v1/" + url, null, "", appendHeaders(headers, "accept-encoding", "identity"));
    }

    private Response fileMetadata(String url, String query, String... headers) {
        return send(HttpMethod.GET, "/v1/metadata/" + url, query, "", headers);
    }

    private String readMarker(String root) {
        return readStoredBlob(root + "/.dial-resource");
    }

    private String aggregateEtag(String root) {
        Response metadata = send(HttpMethod.GET, "/v2/metadata/" + root, null, "");
        verify(metadata, 200);
        return json(metadata).path("etag").asText();
    }

    private static void assertCreationGuidance(Response response, String root) {
        verify(response, 404);
        assertTrue(response.body().contains("PUT /v2/" + root), response::body);
    }

    private void assertFileMetadata(Response response, String root, String filePath, String body, String mimeType) {
        JsonNode metadata = json(response);
        assertEquals(root + "/files/" + filePath, metadata.path("url").asText());
        assertEquals("ITEM", metadata.path("nodeType").asText());
        assertEquals(root.startsWith("skills/") ? "SKILL" : "PROJECT", metadata.path("resourceType").asText());
        assertEquals(bucket, metadata.path("bucket").asText());
        assertEquals(filePath.substring(filePath.lastIndexOf('/') + 1), metadata.path("name").asText());
        String rootPath = root.substring(root.indexOf('/', root.indexOf('/') + 1) + 1);
        String relativeParent = filePath.contains("/") ? "/" + filePath.substring(0, filePath.lastIndexOf('/')) : "";
        assertEquals(rootPath + "/files" + relativeParent, metadata.path("parentPath").asText());
        assertEquals(bytes(body).length, metadata.path("contentLength").asInt());
        assertEquals(mimeType, metadata.path("contentType").asText());
        assertTrue(metadata.hasNonNull("createdAt"), metadata::toString);
        assertTrue(metadata.hasNonNull("updatedAt"), metadata::toString);
        assertTrue(metadata.hasNonNull("author"), metadata::toString);
        assertTrue(metadata.hasNonNull("etag"), metadata::toString);
        assertEquals(root, metadata.path("resource").path("url").asText());
        assertFalse(metadata.path("resource").path("etag").asText().isEmpty());
    }

    private void assertFolder(JsonNode metadata, String url, String name, String parentPath) {
        assertEquals("FOLDER", metadata.path("nodeType").asText());
        assertEquals(url, metadata.path("url").asText());
        assertEquals(name, metadata.path("name").asText());
        assertEquals(parentPath, metadata.path("parentPath").asText());
        assertEquals(bucket, metadata.path("bucket").asText());
    }

    private void assertLogicalMetadata(JsonNode metadata, String root) {
        String url = metadata.path("url").asText();
        assertTrue(url.startsWith(root + "/files/"), metadata::toString);
        assertFalse(url.contains("/.dial-resource"), metadata::toString);
        assertFalse(url.contains("/v/"), metadata::toString);
        if (metadata.path("nodeType").asText().equals("ITEM")) {
            assertEquals(root, metadata.path("resource").path("url").asText());
            assertEquals(aggregateEtag(root), metadata.path("resource").path("etag").asText());
            String rootPath = root.substring(root.indexOf('/', root.indexOf('/') + 1) + 1);
            assertTrue(metadata.path("parentPath").asText().startsWith(rootPath + "/files"), metadata::toString);
        }
        for (JsonNode item : metadata.path("items")) {
            assertLogicalMetadata(item, root);
        }
    }

    private static Set<String> itemUrls(JsonNode metadata) {
        Set<String> urls = new HashSet<>();
        for (JsonNode item : metadata.path("items")) {
            urls.add(item.path("url").asText());
            urls.addAll(itemUrls(item));
        }
        return urls;
    }

    private static void assertPermissions(JsonNode metadata, boolean included) {
        if (included) {
            assertEquals(List.of("READ"), ProxyUtil.convertToObject(metadata.path("permissions").toString(), List.class));
        } else {
            assertFalse(metadata.has("permissions"), metadata::toString);
        }
        for (JsonNode item : metadata.path("items")) {
            assertPermissions(item, included);
        }
    }

    private void acceptReadShare(String url) {
        Response share = operationRequest("/v1/ops/resource/share/create", """
                {"invitationType":"link","resources":[{"url":"%s","permissions":["READ"]}]}
                """.formatted(url));
        verify(share, 200);
        InvitationLink invitation = ProxyUtil.convertToObject(share.body(), InvitationLink.class);
        verify(send(HttpMethod.GET, invitation.invitationLink(), "accept=true", null, "api-key", "proxyKey2"), 200);
    }

    private void assertReadOnlyAccess(String root, String body, String... headers) {
        String url = root + "/files/refs/a.txt";
        verify(fileRequest(HttpMethod.GET, url, headers), 200, body);
        verify(fileMetadata(url, null, headers), 200);
        verify(fileMetadata(root + "/files/refs/", "recursive=true", headers), 200);
        verify(putFile(url, "denied write", MIME_TYPE, headers), 403, "You don't have an access to: " + root);
        verify(fileRequest(HttpMethod.DELETE, url, headers), 403, "You don't have an access to: " + root);
        verify(fileRequest(HttpMethod.GET, url, headers), 200, body);
    }

    private static void assertEvent(EventStream events, String url, String action) {
        JsonNode event = json(events.take());
        assertEquals(url, event.path("url").asText());
        assertEquals(action, event.path("action").asText());
        assertFalse(event.path("url").asText().contains("/.dial-resource"));
        assertFalse(event.path("url").asText().contains("/v/"));
    }

    @SneakyThrows
    private BinaryResponse downloadRaw(CloseableHttpClient rawClient, String path, String encoding, String range) {
        HttpUriRequestBase request = new HttpUriRequestBase("GET", URI.create("http://127.0.0.1:" + serverPort + path));
        request.setHeader("api-key", "proxyKey1");
        request.setHeader("accept-encoding", encoding);
        request.setHeader("origin", "http://localhost");
        if (!range.isEmpty()) {
            request.setHeader("range", range);
        }
        return rawClient.execute(request, response -> {
            Map<String, String> responseHeaders = new HashMap<>();
            for (Header header : response.getHeaders()) {
                responseHeaders.put(header.getName().toLowerCase(Locale.ROOT), header.getValue());
            }
            return new BinaryResponse(response.getCode(), response.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(response.getEntity()),
                    responseHeaders);
        });
    }

    @SneakyThrows
    private static byte[] decodeBody(BinaryResponse response) {
        if ("gzip".equals(response.headers().get("content-encoding"))) {
            try (InputStream stream = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
                return stream.readAllBytes();
            }
        }
        return response.body();
    }

    private void executeMeasuredRequest(HttpMethod method, String path, String body) {
        Response response = method == HttpMethod.PUT ? sendMultipart(path, oneFile(body, "text/plain"))
                : send(HttpMethod.GET, path, null, "", "accept-encoding", "identity");
        verify(response, 200);
        if (method == HttpMethod.GET) {
            assertEquals(body, response.body());
        }
    }

    private void evictPersistedResource(String root) {
        ResourceDescriptor marker = ResourceDescriptorFactory.fromAnyUrl(root + "/.dial-resource", encryptionService);
        String committed = readMarker(root);
        // Wait for write-behind before eviction; invalidating a queued marker would otherwise discard a commit.
        awaitCondition(() -> {
            try {
                return committed.equals(readBlob(marker));
            } catch (RuntimeException e) {
                // The filesystem marker may be briefly absent while write-behind replaces it.
                for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                    if (cause instanceof FileNotFoundException) {
                        return false;
                    }
                }
                throw e;
            }
        });
        ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(root, encryptionService);
        dial.getProxy().getResourceService().invalidate(List.of(resource.getBucketLocation() + resource.getType().group() + "/"));
    }

    @SneakyThrows
    private String readBlob(ResourceDescriptor descriptor) {
        Blob blob = dial.getProxy().getStorage().load(descriptor.getAbsoluteFilePath());
        if (blob == null) {
            return null;
        }
        try (InputStream payload = blob.getPayload().openStream()) {
            if ("gzip".equals(blob.getMetadata().getContentMetadata().getContentEncoding())) {
                try (InputStream decoded = new GZIPInputStream(payload)) {
                    return new String(decoded.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return new String(payload.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static String[] appendHeaders(String[] headers, String... additional) {
        List<String> values = new ArrayList<>(Arrays.asList(headers));
        values.addAll(Arrays.asList(additional));
        return values.toArray(String[]::new);
    }

    private static String header(Response response, String name) {
        return response.headers().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static JsonNode json(Response response) {
        return json(response.body());
    }

    @SneakyThrows
    private static JsonNode json(String body) {
        return ProxyUtil.MAPPER.readTree(body);
    }

    private record Condition(String name, String value, int getExisting, int getAbsent, int putExisting, int putAbsent,
                             int deleteExisting, int deleteAbsent) {
    }

    private record BinaryResponse(int status, byte[] body, Map<String, String> headers) {
    }
}

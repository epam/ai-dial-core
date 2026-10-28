package com.epam.aidial.core.storage.blobstore;

import com.epam.aidial.core.storage.FileUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlobStorageUploadTest {

    private Path testDir;
    private BlobStorage storage;

    @BeforeEach
    void init() throws IOException {
        testDir = FileUtil.baseTestPath(BlobStorageUploadTest.class);
        FileUtil.deleteDir(testDir);
        FileUtil.createDir(testDir.resolve("test"));
        ObjectMapper mapper = new ObjectMapper();
        String config = """
                {
                    "bucket": "test",
                    "provider": "filesystem",
                    "identity": "access-key",
                    "credential": "secret-key",
                    "overrides": {
                      "jclouds.filesystem.basedir": %s
                    }
                  }
                """.formatted(mapper.writeValueAsString(testDir.toString()));
        storage = new BlobStorage(mapper.readValue(config, Storage.class));
    }

    @AfterEach
    void destroy() throws InterruptedException {
        if (storage != null) {
            storage.close();
        }
        // The filesystem provider's multipart support leaves a memory-mapped file handle that Windows only
        // releases once the JVM garbage-collects it, so a prompt delete can see it as still open.
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                FileUtil.deleteDir(testDir);
                return;
            } catch (Exception e) {
                System.gc();
                Thread.sleep(50);
            }
        }
        FileUtil.deleteDir(testDir);
    }

    @Test
    void testCopyKeepsContentAndUserMetadata() {
        String from = "public/models/source";
        String to = "public/models/target";
        storage.store(from, "application/json", "gzip", Map.of("etag", "abc"), "{}".getBytes(StandardCharsets.UTF_8));

        boolean copied = storage.copy(from, to, null);
        assertTrue(copied);

        BlobMetadata meta = storage.meta(to);
        assertEquals("application/json", meta.getContentMetadata().getContentType());
        assertEquals("gzip", meta.getContentMetadata().getContentEncoding());
        assertEquals("abc", meta.getUserMetadata().get("etag"));
    }

    @Test
    void testUploadSmallBlobKeepsAllAttributes() throws IOException {
        String path = "public/models/small";
        BlobAttributes attributes = new BlobAttributes("text/plain", null, "attachment; filename=a.txt", "en", "no-cache",
                Map.of("author", "user1"));
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);

        storage.upload(path, attributes, new ByteArrayInputStream(content));

        Blob blob = storage.load(path);
        assertArrayEquals(content, readAll(blob));
        BlobMetadata meta = blob.getMetadata();
        assertEquals("text/plain", meta.getContentMetadata().getContentType());
        assertEquals("attachment; filename=a.txt", meta.getContentMetadata().getContentDisposition());
        assertEquals("en", meta.getContentMetadata().getContentLanguage());
        assertEquals("no-cache", meta.getContentMetadata().getCacheControl());
        assertEquals("user1", meta.getUserMetadata().get("author"));
    }

    @Test
    void testUploadLargeBlobUsesMultipart() throws IOException {
        String path = "public/models/large";
        int size = (int) (BlobStorage.UPLOAD_PART_SIZE_BYTES * 1.5);
        byte[] content = randomBytes(size);
        BlobAttributes attributes = new BlobAttributes("application/octet-stream", null, null, null, null, Map.of("k", "v"));

        storage.upload(path, attributes, new ByteArrayInputStream(content));

        Blob blob = storage.load(path);
        assertArrayEquals(content, readAll(blob));
        assertEquals("application/octet-stream", blob.getMetadata().getContentMetadata().getContentType());
        assertEquals("v", blob.getMetadata().getUserMetadata().get("k"));
    }

    @Test
    void testUploadExactlyOnePartUsesSinglePut() throws IOException {
        String path = "public/models/exact";
        byte[] content = randomBytes(BlobStorage.UPLOAD_PART_SIZE_BYTES);
        BlobAttributes attributes = new BlobAttributes("application/octet-stream", null, null, null, null, Map.of());

        storage.upload(path, attributes, new ByteArrayInputStream(content));

        assertArrayEquals(content, readAll(storage.load(path)));
    }

    @Test
    void testUploadAbortsMultipartOnFailure() {
        String path = "public/models/aborted";
        int size = (int) (BlobStorage.UPLOAD_PART_SIZE_BYTES * 1.5);
        byte[] content = randomBytes(size);
        BlobAttributes attributes = new BlobAttributes("application/octet-stream", null, null, null, null, Map.of());
        InputStream failingAfterFirstPart = failAfterBytes(content, BlobStorage.UPLOAD_PART_SIZE_BYTES);

        assertThrows(IOException.class, () -> storage.upload(path, attributes, failingAfterFirstPart));

        assertFalse(storage.exists(path), "a failed multipart upload must not leave a blob behind");
    }

    private static byte[] readAll(Blob blob) throws IOException {
        try (InputStream in = blob.getPayload().openStream()) {
            return in.readAllBytes();
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return bytes;
    }

    private static InputStream failAfterBytes(byte[] content, int failAfter) {
        ByteArrayInputStream delegate = new ByteArrayInputStream(content);
        return new InputStream() {
            private int read = 0;

            @Override
            public int read() throws IOException {
                if (read++ >= failAfter) {
                    throw new IOException("simulated upstream failure");
                }
                return delegate.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (read >= failAfter) {
                    throw new IOException("simulated upstream failure");
                }
                int toRead = Math.min(len, failAfter - read);
                int n = delegate.read(b, off, toRead);
                if (n > 0) {
                    read += n;
                }
                return n;
            }
        };
    }
}

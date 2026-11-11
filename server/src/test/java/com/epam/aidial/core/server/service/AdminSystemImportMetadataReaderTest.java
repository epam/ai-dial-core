package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.blobstore.BlobAttributes;
import com.epam.aidial.core.storage.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminSystemImportMetadataReaderTest {

    private final AdminSystemImportMetadataReader reader = new AdminSystemImportMetadataReader();

    // ---- manifest ----

    @Test
    void testManifestIsRead() throws IOException {
        SystemArchiveManifest manifest = readManifest(
                "{\"formatVersion\":1,\"coreVersion\":\"0.50.0\",\"createdAt\":\"2026-10-26T18:53:55Z\",\"unknown\":1}");

        assertEquals(new SystemArchiveManifest(1, "0.50.0", "2026-10-26T18:53:55Z"), manifest);
    }

    @Test
    void testManifestThatIsNotJsonIsRejected() {
        assertRejected(() -> readManifest("not json"));
    }

    @Test
    void testOversizedManifestIsRejected() {
        assertRejected(() -> readManifest("{\"formatVersion\":1,\"coreVersion\":\"0.50.0\",\"padding\":\"" + "x".repeat(70_000) + "\"}"));
    }

    // ---- metadata ----

    @Test
    void testMetadataIsReadIntoBlobAttributes() throws IOException {
        String metadata = "{\"contentType\":\"text/plain\",\"contentEncoding\":\"gzip\",\"contentDisposition\":\"attachment\","
                + "\"contentLanguage\":\"en\",\"cacheControl\":\"no-cache\",\"creationDate\":\"2025-03-04T05:06:07Z\","
                + "\"userMetadata\":{\"etag\":\"abc\"},\"unknown\":1}";

        BlobAttributes attributes = readMetadata(metadata);

        assertEquals(new BlobAttributes("text/plain", "gzip", "attachment", "en", "no-cache", Map.of("etag", "abc")), attributes);
    }

    @Test
    void testMissingUserMetadataIsEmpty() throws IOException {
        assertEquals(Map.of(), readMetadata("{\"contentType\":\"text/plain\"}").userMetadata());
    }

    @Test
    void testMetadataThatIsNotJsonIsRejected() {
        assertRejected(() -> readMetadata("not json"));
    }

    @Test
    void testControlCharacterInMetadataIsRejected() {
        assertRejected(() -> readMetadata("{\"contentType\":\"text/plain\",\"userMetadata\":{\"k\":\"a\\u0007b\"}}"));
        assertRejected(() -> readMetadata("{\"contentType\":\"text/plain\",\"unknown\":\"a\\u0007b\"}"));
    }

    @Test
    void testOversizedMetadataIsRejected() {
        assertRejected(() -> readMetadata("{\"contentType\":\"text/plain\",\"userMetadata\":{\"k\":\"" + "x".repeat(70_000) + "\"}}"));
    }

    // ---- helpers ----

    private SystemArchiveManifest readManifest(String content) throws IOException {
        return reader.readManifest(zipAtEntry(content));
    }

    private BlobAttributes readMetadata(String content) throws IOException {
        return reader.readMetadata(zipAtEntry(content));
    }

    private static ZipInputStream zipAtEntry(String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("entry"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()), StandardCharsets.UTF_8);
        zip.getNextEntry();
        return zip;
    }

    private static void assertRejected(Executable check) {
        AdminSystemImportRejectedException e = assertThrows(AdminSystemImportRejectedException.class, check);
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }
}

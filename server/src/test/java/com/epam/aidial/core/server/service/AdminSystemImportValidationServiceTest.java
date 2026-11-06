package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.util.CoreVersion;
import com.epam.aidial.core.storage.blobstore.BlobAttributes;
import com.epam.aidial.core.storage.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminSystemImportValidationServiceTest {

    private static final String MANIFEST = ".dial-system-export/manifest.json";

    private final AdminSystemImportValidationService service = new AdminSystemImportValidationService(CoreVersion.parse("0.50.0"));

    // ---- manifest ----

    @Test
    void testValidManifestIsAccepted() {
        assertDoesNotThrow(() -> validateManifest(MANIFEST, "{\"formatVersion\":1,\"coreVersion\":\"0.50.0\"}"));
        assertDoesNotThrow(() -> validateManifest(MANIFEST, "{\"formatVersion\":1,\"coreVersion\":\"0.49.2\",\"createdAt\":\"x\"}"));
    }

    @Test
    void testManifestNotFirstIsRejected() {
        assertRejected(() -> validateManifest("public/models/m1", "{}"));
        assertRejected(() -> service.validateManifest(null, zipWith(MANIFEST, "{}")));
    }

    @Test
    void testUnsupportedFormatVersionIsRejected() {
        assertRejected(() -> validateManifest(MANIFEST, "{\"formatVersion\":2,\"coreVersion\":\"0.50.0\"}"));
        assertRejected(() -> validateManifest(MANIFEST, "{\"coreVersion\":\"0.50.0\"}"));
    }

    @Test
    void testArchiveFromNewerReleaseIsRejected() {
        assertRejected(() -> validateManifest(MANIFEST, "{\"formatVersion\":1,\"coreVersion\":\"0.51.0\"}"));
    }

    @Test
    void testManifestThatIsNotJsonIsRejected() {
        assertRejected(() -> validateManifest(MANIFEST, "not json"));
    }

    @Test
    void testOversizedManifestIsRejected() {
        String manifest = "{\"formatVersion\":1,\"coreVersion\":\"0.50.0\",\"padding\":\"" + "x".repeat(70_000) + "\"}";
        assertRejected(() -> validateManifest(MANIFEST, manifest));
    }

    // ---- blob names ----

    @Test
    void testValidNamesAreAccepted() {
        for (String name : List.of("public/files/alice/a.txt", "platform/models/m1", "public/skills/folder/.dial-folder")) {
            assertDoesNotThrow(() -> service.validateBlobName(name), name);
        }
    }

    @Test
    void testPathTraversalIsRejected() {
        assertRejected(() -> service.validateBlobName("public/../Users/alice/files/x"));
    }

    @Test
    void testOtherBucketIsRejected() {
        assertRejected(() -> service.validateBlobName("Users/alice/files/x"));
    }

    @Test
    void testBackslashIsRejected() {
        assertRejected(() -> service.validateBlobName("public\\files\\x"));
    }

    @Test
    void testControlCharacterIsRejected() {
        assertRejected(() -> service.validateBlobName("public/files/a\u0001b"));
        assertRejected(() -> service.validateBlobName("public/files/a\u0000b"));
    }

    @Test
    void testLongNameIsRejected() {
        assertRejected(() -> service.validateBlobName("public/files/" + "a".repeat(1100)));
    }

    @Test
    void testOtherInvalidNamesAreRejected() {
        for (String name : List.of("", "/public/files/x", "public//x", "public/./x", "public/files/x/")) {
            assertRejected(() -> service.validateBlobName(name));
        }
    }

    @Test
    void testCekEntryIsRejected() {
        assertRejected(() -> service.validateBlobName("platform/encryption_keys/cek"));
    }

    @Test
    void testCredentialsEntryIsRejected() {
        assertRejected(() -> service.validateBlobName("public/credentials/x"));
    }

    @Test
    void testExcludedGroupIsMatchedWithoutRegardToCase() {
        assertRejected(() -> service.validateBlobName("public/Encryption_Keys/cek"));
    }

    @Test
    void testExcludedWordsDeeperInThePathAreAccepted() {
        assertDoesNotThrow(() -> service.validateBlobName("public/files/alice/encryption_keys/notes.txt"));
    }

    // ---- metadata ----

    @Test
    void testMetadataIsReadIntoBlobAttributes() throws IOException {
        String metadata = "{\"contentType\":\"text/plain\",\"contentEncoding\":\"gzip\",\"contentDisposition\":\"attachment\","
                + "\"contentLanguage\":\"en\",\"cacheControl\":\"no-cache\",\"userMetadata\":{\"etag\":\"abc\"},\"unknown\":1}";

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
    }

    @Test
    void testOversizedMetadataIsRejected() {
        assertRejected(() -> readMetadata("{\"contentType\":\"text/plain\",\"userMetadata\":{\"k\":\"" + "x".repeat(70_000) + "\"}}"));
    }

    // ---- helpers ----

    private void validateManifest(String name, String content) throws IOException {
        ZipInputStream zip = zipWith(name, content);
        service.validateManifest(zip.getNextEntry(), zip);
    }

    private BlobAttributes readMetadata(String content) throws IOException {
        ZipInputStream zip = zipWith(".dial-system-export/metadata/public/files/x", content);
        zip.getNextEntry();
        return service.readMetadata(zip);
    }

    private static ZipInputStream zipWith(String name, String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new ZipInputStream(new ByteArrayInputStream(out.toByteArray()), StandardCharsets.UTF_8);
    }

    private static void assertRejected(Executable check) {
        AdminSystemImportRejectedException e = assertThrows(AdminSystemImportRejectedException.class, check);
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }
}

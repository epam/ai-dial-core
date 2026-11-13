package com.epam.aidial.core.server.service;

import com.epam.aidial.core.credentials.exception.CekEncryptionException;
import com.epam.aidial.core.credentials.keymanagement.KeyManagementService;
import com.epam.aidial.core.server.util.CoreVersion;
import com.epam.aidial.core.storage.blobstore.BlobAttributes;
import com.epam.aidial.core.storage.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminSystemImportValidationServiceTest {

    private final KeyManagementService keyManagementService = mock(KeyManagementService.class);
    private final AdminSystemImportValidationService service =
            new AdminSystemImportValidationService(CoreVersion.parse("0.50.0"), false, keyManagementService);
    private final AdminSystemImportValidationService kmsService =
            new AdminSystemImportValidationService(CoreVersion.parse("0.50.0"), true, keyManagementService);

    // ---- manifest ----

    @Test
    void testManifestFirstIsAccepted() {
        assertDoesNotThrow(() -> service.validateManifestEntry(new ZipEntry(".dial-system-export/manifest.json")));
    }

    @Test
    void testManifestNotFirstIsRejected() {
        assertRejected(() -> service.validateManifestEntry(new ZipEntry("public/models/m1")));
        assertRejected(() -> service.validateManifestEntry(null));
    }

    @Test
    void testValidManifestIsAccepted() {
        assertDoesNotThrow(() -> service.validateManifest(new SystemArchiveManifest(1, "0.50.0", null)));
        assertDoesNotThrow(() -> service.validateManifest(new SystemArchiveManifest(1, "0.49.2", "2026-10-26T18:53:55Z")));
    }

    @Test
    void testUnsupportedFormatVersionIsRejected() {
        assertRejected(() -> service.validateManifest(new SystemArchiveManifest(2, "0.50.0", null)));
        assertRejected(() -> service.validateManifest(new SystemArchiveManifest(null, "0.50.0", null)));
    }

    @Test
    void testArchiveFromNewerReleaseIsRejected() {
        assertRejected(() -> service.validateManifest(new SystemArchiveManifest(1, "0.51.0", null)));
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
    void testCekEntryIsRejectedWithoutKms() {
        assertRejected(() -> service.validateBlobName("platform/encryption_keys/cek"));
    }

    @Test
    void testCekEntryIsAcceptedWithKms() {
        assertDoesNotThrow(() -> kmsService.validateBlobName("platform/encryption_keys/cek"));
        assertDoesNotThrow(() -> kmsService.validateBlobName("public/encryption_keys/cek"));
    }

    @Test
    void testCredentialsEntryIsRejected() {
        assertRejected(() -> service.validateBlobName("public/credentials/x"));
        assertRejected(() -> kmsService.validateBlobName("public/credentials/x"));
    }

    @Test
    void testExcludedGroupIsMatchedWithoutRegardToCase() {
        assertRejected(() -> service.validateBlobName("public/Encryption_Keys/cek"));
        assertRejected(() -> kmsService.validateBlobName("public/Credentials/x"));
    }

    @Test
    void testExcludedWordsDeeperInThePathAreAccepted() {
        assertDoesNotThrow(() -> service.validateBlobName("public/files/alice/encryption_keys/notes.txt"));
    }

    // ---- encryption keys ----

    @Test
    void testEncryptionKeysAreRecognizedAtTheTypePositionOnly() {
        assertTrue(service.isEncryptionKey("platform/encryption_keys/cek"));
        assertTrue(service.isEncryptionKey("public/Encryption_Keys/cek"));
        assertFalse(service.isEncryptionKey("public/files/alice/encryption_keys/notes.txt"));
        assertFalse(service.isEncryptionKey("platform/models/m1"));
    }

    @Test
    void testEncryptionKeyThatTheKmsUnwrapsIsAccepted() {
        byte[] wrapped = "wrapped".getBytes(StandardCharsets.UTF_8);

        assertDoesNotThrow(() -> kmsService.validateEncryptionKey("public/encryption_keys/cek", plain(), wrapped));

        verify(keyManagementService).decrypt(wrapped);
    }

    @Test
    void testGzipEncodedEncryptionKeyIsUnwrappedAfterDecoding() throws IOException {
        byte[] wrapped = "wrapped".getBytes(StandardCharsets.UTF_8);

        assertDoesNotThrow(() -> kmsService.validateEncryptionKey("public/encryption_keys/cek", gzipped(), gzip(wrapped)));

        verify(keyManagementService).decrypt(wrapped);
    }

    @Test
    void testEncryptionKeyThatTheKmsCannotUnwrapIsRejected() {
        when(keyManagementService.decrypt(any())).thenThrow(new CekEncryptionException("Decryption error", new RuntimeException()));

        assertRejected(() -> kmsService.validateEncryptionKey("public/encryption_keys/cek", plain(), new byte[] {1, 2, 3}));
    }

    @Test
    void testEmptyAndOversizedEncryptionKeysAreRejectedWithoutAskingTheKms() throws IOException {
        assertRejected(() -> kmsService.validateEncryptionKey("public/encryption_keys/cek", plain(), new byte[0]));
        assertRejected(() -> kmsService.validateEncryptionKey("public/encryption_keys/cek", plain(), new byte[64 * 1024 + 1]));
        byte[] bomb = gzip(new byte[10 * 1024 * 1024]);
        assertRejected(() -> kmsService.validateEncryptionKey("public/encryption_keys/cek", gzipped(), bomb));

        verifyNoInteractions(keyManagementService);
    }

    @Test
    void testEncryptionKeyWithBadEncodingIsRejectedWithoutAskingTheKms() {
        assertRejected(() -> kmsService.validateEncryptionKey("public/encryption_keys/cek", gzipped(), new byte[] {1, 2, 3}));
        assertRejected(() -> kmsService.validateEncryptionKey("public/encryption_keys/cek", encoded("br"), new byte[] {1, 2, 3}));

        verifyNoInteractions(keyManagementService);
    }

    @Test
    void testKmsFailureIsNotReportedAsInvalidArchive() {
        when(keyManagementService.decrypt(any())).thenThrow(new IllegalStateException("KMS unavailable"));

        assertThrows(IllegalStateException.class, () -> kmsService.validateEncryptionKey("public/encryption_keys/cek", plain(), new byte[] {1}));
    }

    private static BlobAttributes plain() {
        return encoded(null);
    }

    private static BlobAttributes gzipped() {
        return encoded("gzip");
    }

    private static BlobAttributes encoded(String contentEncoding) {
        return new BlobAttributes("application/octet-stream", contentEncoding, null, null, null, Map.of());
    }

    private static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content);
        }
        return out.toByteArray();
    }

    private static void assertRejected(Executable check) {
        AdminSystemImportRejectedException e = assertThrows(AdminSystemImportRejectedException.class, check);
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }
}

package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.util.CoreVersion;
import com.epam.aidial.core.storage.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminSystemImportValidationServiceTest {

    private final AdminSystemImportValidationService service = new AdminSystemImportValidationService(CoreVersion.parse("0.50.0"));

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

    private static void assertRejected(Executable check) {
        AdminSystemImportRejectedException e = assertThrows(AdminSystemImportRejectedException.class, check);
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }
}

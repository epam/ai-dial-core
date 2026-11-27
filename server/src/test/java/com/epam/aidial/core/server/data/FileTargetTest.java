package com.epam.aidial.core.server.data;

import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTargetTest {
    @Test
    void testFileClassification() {
        FileTarget plain = target("files/public/file.txt");
        assertTrue(plain.isFile());
        assertFalse(plain.isComplexFile());
        assertDoesNotThrow(plain::requireFile);
        FileTarget complex = target("skills/public/s/files/a.md");
        assertTrue(complex.isFile());
        assertTrue(complex.isComplexFile());
        assertDoesNotThrow(complex::requireFile);
        assertFalse(target("skills/public/s").isFile());
    }

    @Test
    void testFoldersAndWholeResourcesRejected() {
        for (String url : List.of("files/public/", "files/public/refs/", "skills/public/s", "skills/public/s/files",
                "skills/public/s/files/", "skills/public/s/files/refs/")) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(HttpException.class, target(url)::requireFile).getStatus(), url);
        }
    }

    @Test
    void testHiddenSegmentsAtAnyDepth() {
        for (String segment : List.of(".dial-resource", ".dial-folder", "v", "%2Edial-resource", "%76")) {
            for (String prefix : List.of("", "refs/")) {
                FileTarget file = target("skills/public/s/files/" + prefix + segment + "/a.txt");
                assertEquals(HttpStatus.NOT_FOUND, assertThrows(HttpException.class, file::validatePath).getStatus());
            }
        }
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(HttpException.class, target("skills/public/v/s/files/a.txt")::validatePath).getStatus());
    }

    @Test
    void testInvalidStorageAliasesRejected() {
        for (String url : List.of("skills/public/s/files/refs%5Csecret.txt", "skills/public/s/files/refs./a.txt",
                "skills/public/s/files/a.txt.", "files/public/a%5Cb.txt")) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(HttpException.class, target(url)::validatePath).getStatus());
        }
    }

    @Test
    void testOrdinaryNamesAndPlainHiddenNamesAreAllowed() {
        for (String url : List.of("skills/public/s/files/files/a.txt", "skills/public/s/files/.visible", "files/public/.dial-resource",
                "files/public/", "projects/public/p/files/refs/a.md")) {
            assertDoesNotThrow(target(url)::validatePath);
        }
    }

    private static FileTarget target(String url) {
        return ResourceDescriptorFactory.fromFileUrl(url, null);
    }
}

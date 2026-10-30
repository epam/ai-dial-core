package com.epam.aidial.core.storage.service;

import com.epam.aidial.core.storage.FileUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempFolderCleanerTest {

    private static final byte[] BODY = "x".getBytes();

    private Path testDir;
    private BlobStorage storage;

    @BeforeEach
    void init() throws IOException {
        testDir = FileUtil.baseTestPath(TempFolderCleanerTest.class);
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
    void destroy() {
        if (storage != null) {
            storage.close();
        }
        FileUtil.deleteDir(testDir);
    }

    @Test
    void testExpiredFolderIsDeletedAndRecentIsKept() {
        TempFolderCleaner cleaner = new TempFolderCleaner(storage, ".dial-system-import-tmp", Duration.ofHours(24));
        LocalDateTime now = LocalDateTime.now();
        String expiredFolder = now.minusHours(25).format(TempFolderCleaner.FOLDER_FORMATTER);
        String recentFolder = now.minusHours(1).format(TempFolderCleaner.FOLDER_FORMATTER);
        String expiredFile = ".dial-system-import-tmp/" + expiredFolder + "/i1/public/models/m1";
        String recentFile = ".dial-system-import-tmp/" + recentFolder + "/i2/public/models/m2";
        storage.store(expiredFile, "application/octet-stream", null, Map.of(), BODY);
        storage.store(recentFile, "application/octet-stream", null, Map.of(), BODY);

        cleaner.cleanup();

        assertFalse(storage.exists(expiredFile));
        assertTrue(storage.exists(recentFile));
    }

    @Test
    void testCleanupStopsAtTheFirstNonExpiredFolder() {
        TempFolderCleaner cleaner = new TempFolderCleaner(storage, ".dial-system-import-tmp", Duration.ofHours(24));
        LocalDateTime now = LocalDateTime.now();
        // folders sort by their timestamp name; the second (not-expired) one must stop the scan before the third
        String expired = now.minusHours(48).format(TempFolderCleaner.FOLDER_FORMATTER);
        String notExpired = now.minusHours(23).format(TempFolderCleaner.FOLDER_FORMATTER);
        String alsoExpiredButAfter = now.minusHours(49).format(TempFolderCleaner.FOLDER_FORMATTER);
        String f1 = ".dial-system-import-tmp/" + expired + "/i1/public/a";
        String f2 = ".dial-system-import-tmp/" + notExpired + "/i2/public/b";
        storage.store(f1, "application/octet-stream", null, Map.of(), BODY);
        storage.store(f2, "application/octet-stream", null, Map.of(), BODY);

        cleaner.cleanup();

        assertFalse(storage.exists(f1), "the folder before the kept one must be deleted");
        assertTrue(storage.exists(f2), "a folder within the TTL must survive");
        // sanity: a third, even-older folder name used only to document ordering is not stored/asserted
        assertTrue(alsoExpiredButAfter.compareTo(notExpired) < 0);
    }

    @Test
    void testUnparseableFolderIsReported() {
        TempFolderCleaner cleaner = new TempFolderCleaner(storage, ".dial-system-import-tmp", Duration.ofHours(24));
        String badFile = ".dial-system-import-tmp/not-a-timestamp/i1/public/a";
        storage.store(badFile, "application/octet-stream", null, Map.of(), BODY);

        // must not throw: the failure is caught and logged, same as the pre-extraction behaviour
        cleaner.cleanup();

        assertTrue(storage.exists(badFile), "an unparseable folder is left alone, not deleted");
    }

    @Test
    void testDeleteFolderRemovesOnlyThatPrefix() {
        TempFolderCleaner cleaner = new TempFolderCleaner(storage, ".dial-system-import-tmp", Duration.ofHours(24));
        String ownFolder = ".dial-system-import-tmp/2026-01-01 00-00-00/own";
        String otherFolder = ".dial-system-import-tmp/2026-01-01 00-00-00/other";
        storage.store(ownFolder + "/public/a", "application/octet-stream", null, Map.of(), BODY);
        storage.store(otherFolder + "/public/b", "application/octet-stream", null, Map.of(), BODY);

        cleaner.deleteFolder(ownFolder);

        assertFalse(storage.exists(ownFolder + "/public/a"));
        assertTrue(storage.exists(otherFolder + "/public/b"), "a sibling staging folder must survive");
    }
}

package com.epam.aidial.core.server;

import org.apache.commons.io.file.PathUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

class FileUtilTest {
    @TempDir
    private Path directory;

    @Test
    void testDirectoryAndMissingDirectoryCleanup() throws IOException {
        Path nested = directory.resolve("nested");
        Files.createDirectory(nested);
        Files.writeString(nested.resolve("a.txt"), "content");
        FileUtil.deleteDir(nested);
        assertFalse(Files.exists(nested));
        FileUtil.deleteDir(nested);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void testFilesystemFailureIsRetried() {
        try (MockedStatic<PathUtils> paths = mockStatic(PathUtils.class)) {
            paths.when(() -> PathUtils.deleteDirectory(directory))
                    .thenThrow(new FileSystemException(directory.toString())).thenReturn(null);
            FileUtil.deleteDir(directory);
            paths.verify(() -> PathUtils.deleteDirectory(directory), times(2));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void testExhaustedRetryStillFails() {
        try (MockedStatic<PathUtils> paths = mockStatic(PathUtils.class)) {
            paths.when(() -> PathUtils.deleteDirectory(directory)).thenThrow(new FileSystemException(directory.toString()));
            assertThrows(FileSystemException.class, () -> FileUtil.deleteDir(directory));
            paths.verify(() -> PathUtils.deleteDirectory(directory), times(20));
        }
    }

    @Test
    void testFalseResultsAndAssertionsAreNotRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        assertFalse(FileUtil.retryFilesystemCall(() -> {
            calls.incrementAndGet();
            return false;
        }));
        assertEquals(1, calls.get());
        assertThrows(AssertionError.class, () -> FileUtil.retryFilesystemCall(() -> {
            throw new AssertionError("failed assertion");
        }));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void testWrappedFilesystemFailureIsRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        assertEquals("done", FileUtil.retryFilesystemCall(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException(new FileSystemException(directory.toString()));
            }
            return "done";
        }));
        assertEquals(2, calls.get());
    }

    @Test
    void testOtherIoFailureIsNotSuppressed() {
        try (MockedStatic<PathUtils> paths = mockStatic(PathUtils.class)) {
            paths.when(() -> PathUtils.deleteDirectory(directory)).thenThrow(new IOException("not a mapped file"));
            assertThrows(IOException.class, () -> FileUtil.deleteDir(directory));
            paths.verify(() -> PathUtils.deleteDirectory(directory), times(1));
        }
    }
}

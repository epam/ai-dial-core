package com.epam.aidial.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileUtilTest {

    @TempDir
    Path directory;

    @Test
    void testRetryReturnsFalseAndNullWithoutRetrying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        assertFalse(FileUtil.retryFilesystemCall(() -> {
            calls.incrementAndGet();
            return false;
        }));
        assertNull(FileUtil.retryFilesystemCall(() -> {
            calls.incrementAndGet();
            return null;
        }));
        assertEquals(2, calls.get());
    }

    @Test
    void testNonFilesystemFailureIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("provider failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> FileUtil.retryFilesystemCall(() -> {
            calls.incrementAndGet();
            throw failure;
        })));
        assertEquals(1, calls.get());
    }

    @Test
    void testAssertionFailureIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        AssertionError failure = new AssertionError("assertion failed");
        assertSame(failure, assertThrows(AssertionError.class, () -> FileUtil.retryFilesystemCall(() -> {
            calls.incrementAndGet();
            throw failure;
        })));
        assertEquals(1, calls.get());
    }

    @Test
    void testFilesystemCauseRetriesOnlyOnWindows() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException(new FileSystemException("mapped-file"));
        Callable<String> operation = () -> {
            if (calls.incrementAndGet() == 1) {
                throw failure;
            }
            return "done";
        };
        if (File.separatorChar == '\\') {
            assertEquals("done", FileUtil.retryFilesystemCall(operation));
            assertEquals(2, calls.get());
        } else {
            assertSame(failure, assertThrows(IllegalStateException.class, () -> FileUtil.retryFilesystemCall(operation)));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void testFilesystemRetriesAreBounded() {
        AtomicInteger calls = new AtomicInteger();
        FileSystemException failure = new FileSystemException("mapped-file");
        assertSame(failure, assertThrows(FileSystemException.class, () -> FileUtil.retryFilesystemCall(() -> {
            calls.incrementAndGet();
            throw failure;
        })));
        assertEquals(File.separatorChar == '\\' ? 20 : 1, calls.get());
    }

    @Test
    void testDeleteDirRemovesFilesAndAcceptsMissingDirectory() throws Exception {
        Path child = Files.createDirectories(directory.resolve("child"));
        Files.writeString(child.resolve("file.txt"), "body");
        FileUtil.deleteDir(child);
        assertFalse(Files.exists(child));
        FileUtil.deleteDir(child);
        assertFalse(Files.exists(child));
    }
}

package com.epam.aidial.core.storage;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.apache.commons.io.file.PathUtils;

import java.io.File;
import java.net.URI;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.Callable;

@UtilityClass
public class FileUtil {

    public static Path baseTestPath(Class<?> clazz) {
        return resolveRes(clazz.getSimpleName());
    }

    @SneakyThrows
    public static Path resolveRes(String resourcePath) {
        URI resUri = Objects.requireNonNull(FileUtil.class.getClassLoader().getResource(".")).toURI();
        return Paths.get(resUri).getParent().toAbsolutePath().resolve(resourcePath);
    }

    @SneakyThrows
    public static void createDir(Path dir) {
        Files.createDirectories(dir);
    }

    @SneakyThrows
    public static void deleteDir(Path dir) {
        retryFilesystemCall(() -> {
            if (Files.exists(dir)) {
                PathUtils.deleteDirectory(dir);
            }
            return null;
        });
    }

    public static <T> T retryFilesystemCall(Callable<T> call) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                return call.call();
            } catch (Exception e) {
                Throwable cause = e;
                while (cause != null && !(cause instanceof FileSystemException)) {
                    cause = cause.getCause();
                }
                if (File.separatorChar != '\\' || cause == null || attempt == 19) {
                    throw e;
                }
                // Closed JClouds mappings may still hold Windows files until collected.
                System.gc();
                Thread.sleep(50);
            }
        }
    }
}
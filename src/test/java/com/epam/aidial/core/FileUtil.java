package com.epam.aidial.core;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.apache.commons.io.file.PathUtils;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

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
        if (Files.exists(dir)) {
            PathUtils.deleteDirectory(dir);
        }
    }
}

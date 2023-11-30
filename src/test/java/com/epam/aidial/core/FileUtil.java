package com.epam.aidial.core;

import com.epam.aidial.core.config.Storage;
import com.epam.aidial.core.storage.BlobStorage;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.apache.commons.io.file.PathUtils;
import org.jclouds.filesystem.reference.FilesystemConstants;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Properties;

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

    public static BlobStorage buildFsBlobStorage(Path baseDir) {
        Properties properties = new Properties();
        properties.setProperty(FilesystemConstants.PROPERTY_BASEDIR, baseDir.toAbsolutePath().toString());
        Storage storageConfig = new Storage();
        storageConfig.setBucket("test");
        storageConfig.setProvider("filesystem");
        storageConfig.setIdentity("access-key");
        storageConfig.setCredential("secret-key");
        return new BlobStorage(storageConfig, properties);
    }
}

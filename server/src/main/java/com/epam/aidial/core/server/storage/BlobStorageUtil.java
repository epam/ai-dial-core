package com.epam.aidial.core.server.storage;

import com.epam.aidial.core.server.resource.ResourceDescriptor;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.annotation.Nullable;

@UtilityClass
public class BlobStorageUtil {

    @SneakyThrows
    public String getContentType(String fileName) {
        String mimeType = Files.probeContentType(Path.of(fileName));
        return mimeType == null ? "application/octet-stream" : mimeType;
    }

    public String toStoragePath(@Nullable String prefix, String absoluteResourcePath) {
        if (prefix == null) {
            return absoluteResourcePath;
        }

        return prefix + ResourceDescriptor.PATH_SEPARATOR + absoluteResourcePath;
    }
}

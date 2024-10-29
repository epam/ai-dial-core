package com.epam.aidial.core.storage.blobstore;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import javax.annotation.Nullable;

@UtilityClass
public class BlobStorageUtil {

    @SneakyThrows
    public String getContentType(String fileName) {
        String mimeType = MimeMapping.getMimeTypeForFilename(fileName);
        return mimeType == null ? "application/octet-stream" : mimeType;
    }

    public String toStoragePath(@Nullable String prefix, String absoluteResourcePath) {
        if (prefix == null) {
            return absoluteResourcePath;
        }

        return prefix + ResourceDescriptor.PATH_SEPARATOR + absoluteResourcePath;
    }
}

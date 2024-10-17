package com.epam.aidial.core.storage;

import lombok.experimental.UtilityClass;

import javax.annotation.Nullable;

@UtilityClass
public class BlobStorageUtil {

    public static final String PATH_SEPARATOR = "/";
    public static final String PUBLIC_BUCKET = "public";
    public static final String PUBLIC_LOCATION = PUBLIC_BUCKET + PATH_SEPARATOR;

    public String getContentType(String fileName) {
        String mimeType = MimeMapping.getMimeTypeForFilename(fileName);
        return mimeType == null ? "application/octet-stream" : mimeType;
    }

    public boolean isFolder(String path) {
        return path.endsWith(PATH_SEPARATOR);
    }

    public String toStoragePath(@Nullable String prefix, String absoluteResourcePath) {
        if (prefix == null) {
            return absoluteResourcePath;
        }

        return prefix + PATH_SEPARATOR + absoluteResourcePath;
    }
}

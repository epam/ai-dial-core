package com.epam.aidial.core.storage;

import lombok.experimental.UtilityClass;

@UtilityClass
public class BlobStorageUtil {

    public static final String PATH_SEPARATOR = "/";
    private static final char DELIMITER = PATH_SEPARATOR.charAt(0);


    public String normalizePathForQuery(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        // remove leading separator
        if (path.charAt(0) == DELIMITER) {
            path = path.substring(1);
        }

        // add trailing separator if needed
        return path.charAt(path.length() - 1) == DELIMITER ? path : path + PATH_SEPARATOR;
    }

    public String removeLeadingAndTrailingPathSeparators(String path) {
        if (path == null || path.isBlank() || path.equals("/")) {
            return null;
        }
        if (path.charAt(0) == DELIMITER) {
            path = path.substring(1);
        }

        if (path.charAt(path.length() - 1) == DELIMITER) {
            path = path.substring(0, path.length() - 1);
        }

        return path;
    }

    public String buildFilePath(String parentPath, String fileId) {
        if (parentPath == null) {
            return fileId;
        }

        return parentPath + PATH_SEPARATOR + fileId;
    }
}

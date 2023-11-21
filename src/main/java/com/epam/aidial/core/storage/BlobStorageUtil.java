package com.epam.aidial.core.storage;

import com.epam.aidial.core.ProxyContext;
import io.vertx.core.http.impl.MimeMapping;
import lombok.experimental.UtilityClass;

@UtilityClass
public class BlobStorageUtil {

    private static final String USER_ROOT_DIR_PATTERN = "Users/%s/files/%s";

    private static final String API_KEY_ROOT_DIR_PATTERN = "Keys/%s/files/%s";

    public static final String PATH_SEPARATOR = "/";
    private static final char DELIMITER = PATH_SEPARATOR.charAt(0);

    /**
     * Normalize provided path for listing files query by removing leading and adding trailing path separator.
     * For example, path /Users/User1/files/folders will be transformed to Users/User1/files/folders/
     *
     * @return normalized path
     */
    public String normalizePathForQuery(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        if (path.equals(PATH_SEPARATOR)) {
            return path;
        }

        // remove leading separator
        if (path.charAt(0) == DELIMITER) {
            path = path.substring(1);
        }

        // add trailing separator if needed
        return path.charAt(path.length() - 1) == DELIMITER ? path : path + PATH_SEPARATOR;
    }

    public String removeLeadingAndTrailingPathSeparators(String path) {
        if (path == null || path.isBlank() || path.equals(PATH_SEPARATOR)) {
            return "";
        }
        path = removeLeadingPathSeparator(path);
        return removeTrailingPathSeparator(path);
    }

    public String removeLeadingPathSeparator(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return path.charAt(0) == DELIMITER ? path.substring(1) : path;
    }

    public String removeTrailingPathSeparator(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        int length = path.length();
        return path.charAt(length - 1) == DELIMITER ? path.substring(0, length - 1) : path;
    }

    public String buildFilePath(String fileName, String path) {
        path = removeTrailingPathSeparator(path);
        return path + PATH_SEPARATOR + fileName;
    }

    public String getContentType(String fileName) {
        String mimeType = MimeMapping.getMimeTypeForFilename(fileName);
        return mimeType == null ? "application/octet-stream" : mimeType;
    }

    public String buildAbsoluteFilePath(ProxyContext context, String path) {
        return buildAbsoluteFilePath(context.getUserSub(), context.getKey().getProject(), path);
    }

    private String buildAbsoluteFilePath(String userSub, String apiKeyId, String path) {
        path = removeLeadingAndTrailingPathSeparators(path);
        if (userSub != null) {
            return USER_ROOT_DIR_PATTERN.formatted(userSub, path);
        } else {
            return API_KEY_ROOT_DIR_PATTERN.formatted(apiKeyId, path);
        }
    }
}

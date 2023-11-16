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
        if (path == null || path.isBlank() || path.equals(PATH_SEPARATOR)) {
            return "";
        }
        if (path.charAt(0) == DELIMITER) {
            path = path.substring(1);
        }

        if (path.charAt(path.length() - 1) == DELIMITER) {
            path = path.substring(0, path.length() - 1);
        }

        return path;
    }

    public String buildFilePath(String fileName, String path) {
        if (path.charAt(path.length() - 1) == DELIMITER) {
            return path + fileName;
        }

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

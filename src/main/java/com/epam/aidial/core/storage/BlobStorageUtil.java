package com.epam.aidial.core.storage;

import com.epam.aidial.core.ProxyContext;
import io.vertx.core.http.impl.MimeMapping;
import lombok.experimental.UtilityClass;

@UtilityClass
public class BlobStorageUtil {

    private static final String USER_BUCKET_PATTERN = "Users/%s/";

    private static final String API_KEY_BUCKET_PATTERN = "Keys/%s/";

    public static final String PATH_SEPARATOR = "/";

    public String getContentType(String fileName) {
        String mimeType = MimeMapping.getMimeTypeForFilename(fileName);
        return mimeType == null ? "application/octet-stream" : mimeType;
    }

    public String buildUserBucket(ProxyContext context) {
        String userSub = context.getUserSub();
        String apiKeyId = context.getProject();

        if (userSub != null) {
            return USER_BUCKET_PATTERN.formatted(userSub);
        }

        if (apiKeyId != null) {
            return API_KEY_BUCKET_PATTERN.formatted(apiKeyId);
        }

        throw new IllegalArgumentException("Can't find user bucket. Either user sub or api-key project must be provided");
    }

    public boolean isFolder(String path) {
        return path.endsWith(PATH_SEPARATOR);
    }
}

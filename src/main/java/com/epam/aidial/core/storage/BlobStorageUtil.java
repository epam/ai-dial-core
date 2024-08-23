package com.epam.aidial.core.storage;

import com.epam.aidial.core.ProxyContext;
import io.vertx.core.http.impl.MimeMapping;
import lombok.experimental.UtilityClass;

import javax.annotation.Nullable;

@UtilityClass
public class BlobStorageUtil {

    public static final String PATH_SEPARATOR = "/";

    public static final String PUBLIC_BUCKET = "public";
    public static final String PUBLIC_LOCATION = PUBLIC_BUCKET + PATH_SEPARATOR;

    public static final String APPDATA_PATTERN = "appdata/%s";
    private static final String USER_BUCKET_PATTERN = "Users/%s/";
    private static final String API_KEY_BUCKET_PATTERN = "Keys/%s/";

    public String getContentType(String fileName) {
        String mimeType = MimeMapping.getMimeTypeForFilename(fileName);
        return mimeType == null ? "application/octet-stream" : mimeType;
    }

    public String buildUserBucket(ProxyContext context) {
        if (context.getApiKeyData().getPerRequestKey() == null) {
            return buildInitiatorBucket(context);
        } else {
            return API_KEY_BUCKET_PATTERN.formatted(context.getSourceDeployment());
        }
    }

    @Nullable
    public String buildAppDataBucket(ProxyContext context) {
        if (context.getApiKeyData().getPerRequestKey() == null) {
            return null;
        } else {
            return buildInitiatorBucket(context);
        }
    }

    public static String buildInitiatorBucket(ProxyContext context) {
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

    public String toStoragePath(@Nullable String prefix, String absoluteResourcePath) {
        if (prefix == null) {
            return absoluteResourcePath;
        }

        return prefix + PATH_SEPARATOR + absoluteResourcePath;
    }
}

package com.epam.aidial.core.server.util;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AuthBucket;
import com.epam.aidial.core.server.security.EncryptionService;
import lombok.experimental.UtilityClass;

import java.util.Objects;
import javax.annotation.Nullable;

@UtilityClass
public class BucketBuilder {

    public static final String APPDATA_PATTERN = "appdata/%s";
    public static final String USER_BUCKET_PATTERN = "Users/%s/";
    public static final String API_KEY_BUCKET_PATTERN = "Keys/%s/";

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
        return buildInitiatorBucket(context.getUserId(), context.getProject());
    }

    public static String buildInitiatorBucket(ApiKeyData apiKeyData) {
        String userId = apiKeyData.getExtractedClaims() != null ? apiKeyData.getExtractedClaims().userId() : null;
        String project = apiKeyData.getOriginalKey() != null ? apiKeyData.getOriginalKey().getProject() : null;
        return buildInitiatorBucket(userId, project);
    }

    private static String buildInitiatorBucket(String userId, String project) {
        if (userId != null) {
            return USER_BUCKET_PATTERN.formatted(userId);
        }

        if (project != null) {
            return API_KEY_BUCKET_PATTERN.formatted(project);
        }

        throw new IllegalArgumentException("Can't find user bucket. Either user sub or api-key project must be provided");
    }

    public AuthBucket buildBucket(ProxyContext context) {
        EncryptionService encryption = context.getProxy().getEncryptionService();
        String perRequestKey = context.getApiKeyData().getPerRequestKey();

        String userBucketLocation = buildInitiatorBucket(context);
        String userBucket = encryption.encrypt(userBucketLocation);

        String appBucket = null;
        String appBucketLocation = null;

        if (perRequestKey != null) {
            Objects.requireNonNull(context.getSourceDeployment());
            appBucketLocation = API_KEY_BUCKET_PATTERN.formatted(context.getSourceDeployment());
            appBucket = encryption.encrypt(appBucketLocation);
        }

        AuthBucket bucket = new AuthBucket();
        bucket.setUserBucket(userBucket);
        bucket.setUserBucketLocation(userBucketLocation);
        bucket.setAppBucket(appBucket);
        bucket.setAppBucketLocation(appBucketLocation);
        return bucket;
    }
}

package com.epam.aidial.core.server.util;

import com.epam.aidial.core.server.ProxyContext;
import lombok.experimental.UtilityClass;

import javax.annotation.Nullable;

@UtilityClass
public class BucketBuilder {

    public static final String APPDATA_PATTERN = "appdata/%s";
    private static final String USER_BUCKET_PATTERN = "Users/%s/";
    private static final String API_KEY_BUCKET_PATTERN = "Keys/%s/";

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

}

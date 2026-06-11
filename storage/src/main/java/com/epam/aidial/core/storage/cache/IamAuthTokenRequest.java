package com.epam.aidial.core.storage.cache;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4PresignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.time.Duration;
import java.time.Instant;

/**
 * Source of implementation <a href="https://docs.aws.amazon.com/AmazonElastiCache/latest/red-ug/auth-iam.html#auth-iam-Connecting">
 *     Authenticating with IAM</a>.
 */
public class IamAuthTokenRequest {
    private static final String REQUEST_PROTOCOL = "http://";
    private static final String PARAM_ACTION = "Action";
    private static final String PARAM_USER = "User";
    private static final String PARAM_RESOURCE_TYPE = "ResourceType";
    private static final String RESOURCE_TYPE_SERVERLESS_CACHE = "ServerlessCache";
    private static final String ACTION_NAME = "connect";
    private static final String SERVICE_NAME = "elasticache";
    private static final long TOKEN_EXPIRY_SECONDS = 900;

    private final String userId;
    private final String clusterName;
    private final String region;
    private final boolean isServerless;

    public IamAuthTokenRequest(String userId, String clusterName, String region, boolean isServerless) {
        this.userId = userId;
        this.clusterName = clusterName;
        this.region = region;
        this.isServerless = isServerless;
    }

    public String toSignedRequestUri(AwsCredentials credentials) {
        SdkHttpFullRequest request = getSignableRequest();
        SdkHttpFullRequest signed = sign(request, credentials);
        return signed.getUri().toString().replace(REQUEST_PROTOCOL, "");
    }

    private SdkHttpFullRequest getSignableRequest() {
        SdkHttpFullRequest.Builder builder = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.GET)
                .protocol("http")
                .host(clusterName)
                .encodedPath("/")
                .putRawQueryParameter(PARAM_ACTION, ACTION_NAME)
                .putRawQueryParameter(PARAM_USER, userId);
        if (isServerless) {
            builder.putRawQueryParameter(PARAM_RESOURCE_TYPE, RESOURCE_TYPE_SERVERLESS_CACHE);
        }
        return builder.build();
    }

    private SdkHttpFullRequest sign(SdkHttpFullRequest request, AwsCredentials credentials) {
        Aws4PresignerParams params = Aws4PresignerParams.builder()
                .awsCredentials(credentials)
                .signingName(SERVICE_NAME)
                .signingRegion(Region.of(region))
                .expirationTime(Instant.now().plus(Duration.ofSeconds(TOKEN_EXPIRY_SECONDS)))
                .build();
        return Aws4Signer.create().presign(request, params);
    }
}

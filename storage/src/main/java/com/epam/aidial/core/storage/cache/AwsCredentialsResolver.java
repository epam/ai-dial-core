package com.epam.aidial.core.storage.cache;

import com.amazonaws.auth.AWSCredentialsProvider;
import org.redisson.config.Credentials;

import java.net.URISyntaxException;

/**
 * Source of implementation <a href="https://redisson.org/articles/how-to-connect-to-redis-using-elasticache-iam-credential-provider.html">
 *     How to Connect to Redis With the ElastiCache IAM Credential Provider</a>.
 */
public class AwsCredentialsResolver extends BaseCredentialsResolver {

    private static final long TOKEN_EXPIRY_MS = 900_000;

    private final String userName;
    private final IamAuthTokenRequest iamAuthTokenRequest;
    private final AWSCredentialsProvider awsCredentialsProvider;

    private Credentials credentials;
    private long lastTime = System.currentTimeMillis();

    public AwsCredentialsResolver(String userName, IamAuthTokenRequest iamAuthTokenRequest,
                                  AWSCredentialsProvider awsCredentialsProvider) {
        this.userName = userName;
        this.iamAuthTokenRequest = iamAuthTokenRequest;
        this.awsCredentialsProvider = awsCredentialsProvider;
    }

    @Override
    protected Credentials resolve() {
        if (System.currentTimeMillis() - lastTime > TOKEN_EXPIRY_MS || credentials == null) {
            try {
                String token = iamAuthTokenRequest.toSignedRequestUri(awsCredentialsProvider.getCredentials());
                credentials = new Credentials(userName, token);
                lastTime = System.currentTimeMillis();
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return credentials;
    }
}

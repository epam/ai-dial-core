package com.epam.aidial.core.storage.cache;

import org.redisson.config.Credentials;
import org.redisson.config.CredentialsResolver;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Source of implementation <a href="https://redisson.org/articles/how-to-connect-to-redis-using-elasticache-iam-credential-provider.html">
 *     How to Connect to Redis With the ElastiCache IAM Credential Provider</a>.
 */
public class AwsCredentialsResolver implements CredentialsResolver {

    private static final long TOKEN_EXPIRY_MS = 900_000;

    private final String userName;
    private final IamAuthTokenRequest iamAuthTokenRequest;
    private final AwsCredentialsProvider awsCredentialsProvider;

    private volatile CompletionStage<Credentials> future;
    private volatile long lastTime = System.currentTimeMillis();

    public AwsCredentialsResolver(String userName, IamAuthTokenRequest iamAuthTokenRequest,
                                  AwsCredentialsProvider awsCredentialsProvider) {
        this.userName = userName;
        this.iamAuthTokenRequest = iamAuthTokenRequest;
        this.awsCredentialsProvider = awsCredentialsProvider;
    }

    @Override
    public CompletionStage<Credentials> resolve(InetSocketAddress address) {
        if (System.currentTimeMillis() - lastTime > TOKEN_EXPIRY_MS || future == null) {
            String token = iamAuthTokenRequest.toSignedRequestUri(awsCredentialsProvider.resolveCredentials());
            future = CompletableFuture.completedFuture(new Credentials(userName, token));
            lastTime = System.currentTimeMillis();
        }
        return future;
    }
}

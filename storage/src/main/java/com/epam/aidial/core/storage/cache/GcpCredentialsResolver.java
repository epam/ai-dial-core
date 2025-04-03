package com.epam.aidial.core.storage.cache;

import com.google.cloud.iam.credentials.v1.GenerateAccessTokenResponse;
import com.google.cloud.iam.credentials.v1.IamCredentialsClient;
import com.google.cloud.iam.credentials.v1.IamCredentialsSettings;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import lombok.SneakyThrows;
import org.redisson.config.Credentials;
import org.redisson.config.CredentialsResolver;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class GcpCredentialsResolver implements CredentialsResolver {

    private static final Duration LIFETIME = Duration.newBuilder().setSeconds(TimeUnit.MINUTES.toSeconds(15)).build();

    private static final List<String> SCOPES = Collections.singletonList("https://www.googleapis.com/auth/cloud-platform");

    private final IamCredentialsClient iamClient;
    private final String accountName;

    private volatile Timestamp expireTime;

    private volatile CompletionStage<Credentials> future;

    @SneakyThrows
    public GcpCredentialsResolver(String accountName) {
        this.accountName = accountName;
        IamCredentialsSettings settings = IamCredentialsSettings.newHttpJsonBuilder().build();
        this.iamClient = IamCredentialsClient.create(settings);
    }

    @Override
    public CompletionStage<Credentials> resolve(InetSocketAddress address) {
        long now = Instant.now().getEpochSecond();
        if (expireTime == null || now >= expireTime.getSeconds()) {
            GenerateAccessTokenResponse response =
                    this.iamClient.generateAccessToken(
                            this.accountName,
                            Collections.emptyList(),
                            SCOPES,
                            LIFETIME);
            expireTime = response.getExpireTime();
            String token = response.getAccessToken();
            future = CompletableFuture.completedFuture(new Credentials(null, token));
        }
        return future;
    }
}

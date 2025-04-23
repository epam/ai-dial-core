package com.epam.aidial.core.storage.cache;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.google.common.annotations.VisibleForTesting;
import org.redisson.config.Credentials;
import org.redisson.config.CredentialsResolver;

import java.net.InetSocketAddress;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public class AzureCredentialsResolver implements CredentialsResolver {

    private static final long EXPIRATION_WINDOW_IN_SEC = 10;

    static final TokenRequestContext TOKEN_REQUEST_CONTEXT = new TokenRequestContext().addScopes("https://redis.azure.com/.default");

    private final DefaultAzureCredential defaultCredential;

    private final Supplier<OffsetDateTime> now;

    private volatile OffsetDateTime expiresAt;

    private volatile CompletionStage<Credentials> future;

    public AzureCredentialsResolver() {
        this.defaultCredential = new DefaultAzureCredentialBuilder().build();
        this.now = OffsetDateTime::now;
    }

    @VisibleForTesting
    AzureCredentialsResolver(DefaultAzureCredential defaultAzureCredential, Supplier<OffsetDateTime> now) {
        this.defaultCredential = defaultAzureCredential;
        this.now = now;
    }

    @Override
    public CompletionStage<Credentials> resolve(InetSocketAddress address) {
        OffsetDateTime date = now.get().plusSeconds(EXPIRATION_WINDOW_IN_SEC);
        if (expiresAt == null || date.isAfter(expiresAt)) {
            AccessToken accessToken = defaultCredential.getTokenSync(TOKEN_REQUEST_CONTEXT);
            expiresAt = accessToken.getExpiresAt();
            future = CompletableFuture.completedFuture(new Credentials(null, accessToken.getToken()));
        }
        return future;
    }
}

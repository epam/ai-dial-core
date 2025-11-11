package com.epam.aidial.core.storage.cache;

import org.redisson.config.Credentials;
import org.redisson.config.CredentialsResolver;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Credential resolver for local Redis installation
 */
public class LocalRedisCredentialsResolver implements CredentialsResolver {

    private static final long TOKEN_EXPIRY_MS = 900_000;

    private final String userName;
    private final String password;

    private volatile CompletionStage<Credentials> future;
    private volatile long lastTime = System.currentTimeMillis();

    public LocalRedisCredentialsResolver(String userName, String password) {
        this.userName = userName;
        this.password = password;
    }

    @Override
    public CompletionStage<Credentials> resolve(InetSocketAddress address) {
        if (System.currentTimeMillis() - lastTime > TOKEN_EXPIRY_MS || future == null) {
            try {
                future = CompletableFuture.completedFuture(new Credentials(userName, password));
                lastTime = System.currentTimeMillis();
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }
        return future;
    }
}

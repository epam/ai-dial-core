package com.epam.aidial.core.storage.cache;

import lombok.extern.slf4j.Slf4j;
import org.redisson.config.Credentials;
import org.redisson.config.CredentialsResolver;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public abstract class BaseCredentialsResolver implements CredentialsResolver {

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    public CompletionStage<Credentials> resolve(InetSocketAddress address) {
        CompletableFuture<Credentials> future = new CompletableFuture<>();
        executorService.submit(() -> {
            try {
                Credentials credentials = resolve();
                future.complete(credentials);
            } catch (Throwable e) {
                log.error("Error occurred at resolving credentials for access to the cache server", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    protected abstract Credentials resolve();
}

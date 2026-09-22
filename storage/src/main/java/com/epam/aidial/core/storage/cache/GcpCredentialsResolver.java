package com.epam.aidial.core.storage.cache;

import com.google.auth.RequestMetadataCallback;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.common.annotations.VisibleForTesting;
import lombok.SneakyThrows;
import org.redisson.config.Credentials;
import org.redisson.config.CredentialsResolver;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GcpCredentialsResolver implements CredentialsResolver {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private static final List<String> SCOPES = Collections.singletonList("https://www.googleapis.com/auth/cloud-platform");

    private final ImpersonatedCredentials credentials;
    private final ExecutorService executor;

    @SneakyThrows
    public GcpCredentialsResolver(String accountName) {
        this.credentials = ImpersonatedCredentials.newBuilder()
                .setSourceCredentials(GoogleCredentials.getApplicationDefault())
                .setTargetPrincipal(accountName)
                .setScopes(SCOPES)
                .build();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @VisibleForTesting
    GcpCredentialsResolver(ImpersonatedCredentials credentials, ExecutorService executor) {
        this.credentials = credentials;
        this.executor = executor;
    }

    @Override
    public CompletionStage<Credentials> resolve(InetSocketAddress address) {
        CompletableFuture<Credentials> future = new CompletableFuture<>();
        credentials.getRequestMetadata(null, executor, new RequestMetadataCallback() {
            @Override
            public void onSuccess(Map<String, List<String>> metadata) {
                List<String> headers = metadata.get(AUTHORIZATION_HEADER);
                String token = findBearerToken(headers);
                if (token == null) {
                    future.completeExceptionally(new IllegalStateException(
                            "GCP request metadata is missing a valid " + AUTHORIZATION_HEADER + " header"));
                    return;
                }
                future.complete(new Credentials(null, token));
            }

            @Override
            public void onFailure(Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    private static String findBearerToken(List<String> headers) {
        if (headers == null || headers.size() != 1) {
            return null;
        }
        String header = headers.get(0);
        return header.startsWith(BEARER_PREFIX) ? header.substring(BEARER_PREFIX.length()) : null;
    }
}

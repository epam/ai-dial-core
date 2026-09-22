package com.epam.aidial.core.storage.cache;

import com.google.auth.RequestMetadataCallback;
import com.google.auth.oauth2.ImpersonatedCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.config.Credentials;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
public class GcpCredentialsResolverTest {

    @Mock
    private ImpersonatedCredentials credentials;

    @Mock
    private ExecutorService executor;

    @InjectMocks
    private GcpCredentialsResolver resolver;

    @Test
    public void testResolve() {
        doAnswer(invocation -> {
            RequestMetadataCallback callback = invocation.getArgument(2);
            callback.onSuccess(Map.of("Authorization", List.of("Bearer token1")));
            return null;
        }).when(credentials).getRequestMetadata(any(), any(Executor.class), any(RequestMetadataCallback.class));

        InetSocketAddress address = new InetSocketAddress(8080);
        CompletionStage<Credentials> stage = resolver.resolve(address);
        assertNotNull(stage);
        stage.thenAccept(creds -> {
            assertEquals("token1", creds.getPassword());
            assertNull(creds.getUsername());
        });
    }

    @Test
    public void testResolveFailure() {
        RuntimeException error = new RuntimeException("token fetch failed");
        doAnswer(invocation -> {
            RequestMetadataCallback callback = invocation.getArgument(2);
            callback.onFailure(error);
            return null;
        }).when(credentials).getRequestMetadata(any(), any(Executor.class), any(RequestMetadataCallback.class));

        InetSocketAddress address = new InetSocketAddress(8080);
        CompletionStage<Credentials> stage = resolver.resolve(address);
        assertNotNull(stage);
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> stage.toCompletableFuture().join());
        assertEquals(error, thrown.getCause());
    }

    @Test
    public void testResolveMissingAuthorizationHeader() {
        doAnswer(invocation -> {
            RequestMetadataCallback callback = invocation.getArgument(2);
            callback.onSuccess(Map.of());
            return null;
        }).when(credentials).getRequestMetadata(any(), any(Executor.class), any(RequestMetadataCallback.class));

        InetSocketAddress address = new InetSocketAddress(8080);
        CompletionStage<Credentials> stage = resolver.resolve(address);
        assertNotNull(stage);
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> stage.toCompletableFuture().join());
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

    @Test
    public void testResolveRejectsMultipleAuthorizationValues() {
        doAnswer(invocation -> {
            RequestMetadataCallback callback = invocation.getArgument(2);
            callback.onSuccess(Map.of("Authorization", List.of("Bearer token1", "Bearer token2")));
            return null;
        }).when(credentials).getRequestMetadata(any(), any(Executor.class), any(RequestMetadataCallback.class));

        InetSocketAddress address = new InetSocketAddress(8080);
        CompletionStage<Credentials> stage = resolver.resolve(address);
        assertNotNull(stage);
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> stage.toCompletableFuture().join());
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

    @Test
    public void testResolveMalformedAuthorizationHeader() {
        doAnswer(invocation -> {
            RequestMetadataCallback callback = invocation.getArgument(2);
            callback.onSuccess(Map.of("Authorization", List.of("token1")));
            return null;
        }).when(credentials).getRequestMetadata(any(), any(Executor.class), any(RequestMetadataCallback.class));

        InetSocketAddress address = new InetSocketAddress(8080);
        CompletionStage<Credentials> stage = resolver.resolve(address);
        assertNotNull(stage);
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> stage.toCompletableFuture().join());
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }
}

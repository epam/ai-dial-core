package com.epam.aidial.core.server.service;

import org.junit.jupiter.api.Test;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpHttpClientBuilderServiceTest {

    private static McpHttpClientBuilderService.Settings settings(long connectTimeout) {
        McpHttpClientBuilderService.Settings settings = new McpHttpClientBuilderService.Settings();
        settings.setConnectTimeout(connectTimeout);
        return settings;
    }

    @Test
    void builderAlwaysReturnsTheSameSharedHttpClient() throws Exception {
        try (McpHttpClientBuilderService service = new McpHttpClientBuilderService(settings(5000))) {
            HttpClient first = service.httpClientBuilder().build();
            HttpClient second = service.httpClientBuilder().build();

            assertSame(first, second);
            assertInstanceOf(RedirectSafeHttpClient.class, first);
        }
    }

    @Test
    void builderConfigurationCallsAreNoOpsAndStillReturnTheSharedClient() throws Exception {
        try (McpHttpClientBuilderService service = new McpHttpClientBuilderService(settings(5000))) {
            HttpClient shared = service.httpClientBuilder().build();

            HttpClient viaFullyConfiguredBuilder = service.httpClientBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .proxy(ProxySelector.getDefault())
                    .priority(1)
                    .build();

            assertSame(shared, viaFullyConfiguredBuilder);
        }
    }

    @Test
    void followRedirectsThrowsInsteadOfSilentlyDiscardingTheRequestedPolicy() throws Exception {
        try (McpHttpClientBuilderService service = new McpHttpClientBuilderService(settings(5000))) {
            assertThrows(UnsupportedOperationException.class,
                    () -> service.httpClientBuilder().followRedirects(HttpClient.Redirect.ALWAYS));
        }
    }

    @Test
    void versionThrowsInsteadOfSilentlyDiscardingTheRequestedVersion() throws Exception {
        try (McpHttpClientBuilderService service = new McpHttpClientBuilderService(settings(5000))) {
            assertThrows(UnsupportedOperationException.class,
                    () -> service.httpClientBuilder().version(HttpClient.Version.HTTP_2));
        }
    }

    @Test
    void closeClosesTheSharedHttpClient() {
        McpHttpClientBuilderService service = new McpHttpClientBuilderService(settings(5000));
        assertDoesNotThrow(service::close);
    }
}

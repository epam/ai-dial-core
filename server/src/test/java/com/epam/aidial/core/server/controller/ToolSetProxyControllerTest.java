package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.credentials.service.AuthorizationHeaderProvider;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import io.vertx.core.http.HttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolSetProxyControllerTest {

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @Mock
    private AsyncTaskExecutor taskExecutor;

    @Mock
    private DeploymentService deploymentService;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private HttpClient httpClient;

    @Mock
    private UpstreamRouteProvider upstreamRouteProvider;

    @Mock
    private LogStore logStore;

    @Mock
    private AuthorizationHeaderProvider authorizationHeaderProvider;

    @Mock
    private EncryptionService encryptionService;

    @BeforeEach
    void setUp() {
        when(context.getProxy()).thenReturn(proxy);
        when(context.getApiKeyData()).thenReturn(mock(ApiKeyData.class));
        when(context.getUserSub()).thenReturn("userSub");
        when(proxy.getAuthorizationHeaderProvider()).thenReturn(authorizationHeaderProvider);
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor);
        when(proxy.getDeploymentService()).thenReturn(deploymentService);
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
        when(proxy.getClient()).thenReturn(httpClient);
        when(proxy.getUpstreamRouteProvider()).thenReturn(upstreamRouteProvider);
        when(proxy.getLogStore()).thenReturn(logStore);
    }

    @ParameterizedTest
    @CsvSource({"toolset-1", "toolset 1"})
    void testCreateToolSetProxyController(String toolSetName) {
        // Given
        String toolSetId = "toolsets/encrypted-bucket/%s".formatted(toolSetName);
        when(encryptionService.decrypt("encrypted-bucket")).thenReturn("decrypted-bucket/");

        // When
        ToolSetProxyController toolSetProxyController = new ToolSetProxyController(proxy, context, toolSetId);

        // Then
        assertNotNull(toolSetProxyController);
    }
}

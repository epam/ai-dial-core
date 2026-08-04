package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.mcp.McpClientUtils;
import com.epam.aidial.core.server.mcp.McpHttpClientBuilder;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.ConsentService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpSchema;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpResourceControllerTest {

    @Mock
    private Proxy proxy;
    @Mock
    private ProxyContext context;
    @Mock
    private DeploymentService deploymentService;
    @Mock
    private ConsentService consentService;
    @Mock
    private ApplicationSchemaService applicationSchemaService;
    @Mock
    private AsyncTaskExecutor taskExecutor;
    @Mock
    private ApiKeyStore apiKeyStore;
    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private HttpServerRequest request;
    @Mock
    private HttpClientOptions clientOptions;
    @Mock
    private McpHttpClientBuilder mcpHttpClientBuilder;

    private McpResourceController controller;

    @BeforeEach
    void setup() {
        when(proxy.getDeploymentService()).thenReturn(deploymentService);
        when(proxy.getConsentService()).thenReturn(consentService);
        when(proxy.getApplicationSchemaService()).thenReturn(applicationSchemaService);
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor);
        when(proxy.getApiKeyStore()).thenReturn(apiKeyStore);
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
        lenient().when(proxy.getClientOptions()).thenReturn(clientOptions);
        lenient().when(clientOptions.getIdleTimeout()).thenReturn(5000);
        lenient().when(proxy.getMcpHttpClientBuilder()).thenReturn(mcpHttpClientBuilder);
        lenient().when(rateLimiter.limit(any(), any()))
                .thenReturn(Future.succeededFuture(RateLimitResult.SUCCESS));
        lenient().doAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            try {
                return Future.succeededFuture(callable.call());
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        }).when(taskExecutor).submit(any(Callable.class));
        lenient().when(context.getRequest()).thenReturn(request);

        controller = new McpResourceController(proxy, context, "statgpt");
    }

    // --- URI validation ---

    @Test
    void missingUriParam_returns400() {
        when(request.getParam("uri")).thenReturn(null);

        controller.handle();

        verify(context).respond(HttpStatus.BAD_REQUEST, "Missing 'uri' query parameter");
    }

    @Test
    void blankUriParam_returns400() {
        when(request.getParam("uri")).thenReturn("   ");

        controller.handle();

        verify(context).respond(HttpStatus.BAD_REQUEST, "Missing 'uri' query parameter");
    }

    @Test
    void nonUiSchemeUri_returns400() {
        when(request.getParam("uri")).thenReturn("file:///etc/passwd");

        controller.handle();

        verify(context).respond(HttpStatus.BAD_REQUEST, "Resource URI must use the 'ui://' scheme");
    }

    // --- Deployment resolution errors ---

    @Test
    void deploymentNotFound_returns404() {
        when(request.getParam("uri")).thenReturn("ui://widget");
        when(deploymentService.findDeployment(context, "statgpt"))
                .thenThrow(new ResourceNotFoundException("Application is not found: statgpt"));

        controller.handle();

        verify(context).respond(HttpStatus.NOT_FOUND, "Application is not found: statgpt");
    }

    @Test
    void permissionDenied_returns403() {
        when(request.getParam("uri")).thenReturn("ui://widget");
        when(deploymentService.findDeployment(context, "statgpt"))
                .thenThrow(new PermissionDeniedException("Access denied"));

        controller.handle();

        verify(context).respond(HttpStatus.FORBIDDEN, "Access denied");
    }

    @Test
    void noMcpConfig_returns400() {
        when(request.getParam("uri")).thenReturn("ui://widget");
        Application app = new Application();
        when(deploymentService.findDeployment(context, "statgpt")).thenReturn(app);

        controller.handle();

        verify(context).respond(HttpStatus.BAD_REQUEST,
                "Application doesn't support MCP protocol: statgpt");
    }

    // --- MCP auth error passthrough ---

    @Test
    void fetchResource_mcpServerReturns401_respondsUnauthorized() {
        when(request.getParam("uri")).thenReturn("ui://statgpt/chart.html");
        when(deploymentService.findDeployment(context, "statgpt")).thenReturn(appWithMcpEndpoint("http://mcp.example.com/mcp"));

        HttpResponse.ResponseInfo responseInfo = mock(HttpResponse.ResponseInfo.class);
        when(responseInfo.statusCode()).thenReturn(401);
        McpHttpClientTransportAuthorizationException authEx =
                new McpHttpClientTransportAuthorizationException("Unauthorized", responseInfo);

        try (MockedStatic<McpClientUtils> mcpUtils = mockStatic(McpClientUtils.class)) {
            mcpUtils.when(() -> McpClientUtils.withSyncClient(any(), any(), any(), any(), any()))
                    .thenThrow(authEx);

            controller.handle();
        }

        ArgumentCaptor<HttpException> captor = ArgumentCaptor.forClass(HttpException.class);
        verify(context).respond(captor.capture());
        assertEquals(HttpStatus.UNAUTHORIZED, captor.getValue().getStatus());
        assertEquals("MCP server returned 401", captor.getValue().getMessage());
    }

    @Test
    void fetchResource_mcpServerReturns403_respondsForbidden() {
        when(request.getParam("uri")).thenReturn("ui://statgpt/chart.html");
        when(deploymentService.findDeployment(context, "statgpt")).thenReturn(appWithMcpEndpoint("http://mcp.example.com/mcp"));

        HttpResponse.ResponseInfo responseInfo = mock(HttpResponse.ResponseInfo.class);
        when(responseInfo.statusCode()).thenReturn(403);
        McpHttpClientTransportAuthorizationException authEx =
                new McpHttpClientTransportAuthorizationException("Forbidden", responseInfo);

        try (MockedStatic<McpClientUtils> mcpUtils = mockStatic(McpClientUtils.class)) {
            mcpUtils.when(() -> McpClientUtils.withSyncClient(any(), any(), any(), any(), any()))
                    .thenThrow(authEx);

            controller.handle();
        }

        ArgumentCaptor<HttpException> captor = ArgumentCaptor.forClass(HttpException.class);
        verify(context).respond(captor.capture());
        assertEquals(HttpStatus.FORBIDDEN, captor.getValue().getStatus());
        assertEquals("MCP server returned 403", captor.getValue().getMessage());
    }

    @Test
    void fetchResource_mcpServerFails_returns502() {
        when(request.getParam("uri")).thenReturn("ui://statgpt/chart.html");
        when(deploymentService.findDeployment(context, "statgpt")).thenReturn(appWithMcpEndpoint("http://mcp.example.com/mcp"));

        try (MockedStatic<McpClientUtils> mcpUtils = mockStatic(McpClientUtils.class)) {
            mcpUtils.when(() -> McpClientUtils.withSyncClient(any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("connection refused"));

            controller.handle();
        }

        ArgumentCaptor<HttpException> captor = ArgumentCaptor.forClass(HttpException.class);
        verify(context).respond(captor.capture());
        assertEquals(HttpStatus.BAD_GATEWAY, captor.getValue().getStatus());
        assertEquals("Failed to fetch MCP resource", captor.getValue().getMessage());
    }

    // --- sendResourceResponse unit tests ---

    @Test
    void sendResourceResponse_nullContents_returns502() {
        controller.sendResourceResponse(new McpSchema.ReadResourceResult(null));

        verify(context).respond(HttpStatus.BAD_GATEWAY, "Empty resource contents");
    }

    @Test
    void sendResourceResponse_emptyContents_returns502() {
        controller.sendResourceResponse(new McpSchema.ReadResourceResult(List.of()));

        verify(context).respond(HttpStatus.BAD_GATEWAY, "Empty resource contents");
    }

    @Test
    void sendResourceResponse_missingMimeType_returns502() {
        McpSchema.TextResourceContents contents =
                new McpSchema.TextResourceContents("ui://widget", null, "<h1>hi</h1>");

        controller.sendResourceResponse(new McpSchema.ReadResourceResult(List.of(contents)));

        verify(context).respond(HttpStatus.BAD_GATEWAY, "Resource missing mimeType");
    }

    @Test
    void sendResourceResponse_blankMimeType_returns502() {
        McpSchema.TextResourceContents contents =
                new McpSchema.TextResourceContents("ui://widget", "   ", "<h1>hi</h1>");

        controller.sendResourceResponse(new McpSchema.ReadResourceResult(List.of(contents)));

        verify(context).respond(HttpStatus.BAD_GATEWAY, "Resource missing mimeType");
    }

    @Test
    void sendResourceResponse_disallowedMimeType_returns502() {
        McpSchema.TextResourceContents contents =
                new McpSchema.TextResourceContents("ui://widget", "application/octet-stream", "bin");

        controller.sendResourceResponse(new McpSchema.ReadResourceResult(List.of(contents)));

        verify(context).respond(HttpStatus.BAD_GATEWAY, "Unsupported resource mimeType: application/octet-stream");
    }

    @Test
    void sendResourceResponse_mimeTypeWithCrlf_returns502() {
        McpSchema.TextResourceContents contents =
                new McpSchema.TextResourceContents("ui://widget", "text/html\r\nX-Injected: evil", "<h1>hi</h1>");

        controller.sendResourceResponse(new McpSchema.ReadResourceResult(List.of(contents)));

        verify(context).respond(HttpStatus.BAD_GATEWAY, "Unsupported resource mimeType: text/html\r\nX-Injected: evil");
    }

    @Test
    void sendResourceResponse_blobContents_returns502() {
        McpSchema.BlobResourceContents contents =
                new McpSchema.BlobResourceContents("ui://widget", "image/png", "base64data");

        controller.sendResourceResponse(new McpSchema.ReadResourceResult(List.of(contents)));

        verify(context).respond(HttpStatus.BAD_GATEWAY, "Unsupported resource contents type: BlobResourceContents");
    }

    @Test
    void sendResourceResponse_textHtml_setsHeadersAndBody() {
        McpSchema.TextResourceContents contents =
                new McpSchema.TextResourceContents("ui://widget", "text/html", "<h1>Chart</h1>");

        HttpServerResponse httpResponse = mock(HttpServerResponse.class);
        when(context.getResponse()).thenReturn(httpResponse);
        lenient().when(httpResponse.putHeader(anyString(), anyString())).thenReturn(httpResponse);
        when(httpResponse.putHeader(any(CharSequence.class), any(CharSequence.class))).thenReturn(httpResponse);

        controller.sendResourceResponse(new McpSchema.ReadResourceResult(List.of(contents)));

        verify(httpResponse).end("<h1>Chart</h1>");
    }

    // --- helpers ---

    private static Application appWithMcpEndpoint(String endpoint) {
        Application.Mcp mcp = new Application.Mcp();
        mcp.setEndpoint(endpoint);
        mcp.setForwardPerRequestKey(false);
        Application app = new Application();
        app.setMcp(mcp);
        return app;
    }
}

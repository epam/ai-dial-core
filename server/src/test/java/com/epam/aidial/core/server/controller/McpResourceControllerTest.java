package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.ConsentService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Callable;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
    private HttpClient httpClient;
    @Mock
    private HttpServerRequest request;

    private McpResourceController controller;

    @BeforeEach
    void setup() {
        when(proxy.getDeploymentService()).thenReturn(deploymentService);
        when(proxy.getConsentService()).thenReturn(consentService);
        when(proxy.getApplicationSchemaService()).thenReturn(applicationSchemaService);
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor);
        when(proxy.getClient()).thenReturn(httpClient);

        lenient().doAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            try {
                return Future.succeededFuture(callable.call());
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        }).when(taskExecutor).submit(any(Callable.class));

        when(context.getRequest()).thenReturn(request);

        controller = new McpResourceController(proxy, context, "statgpt");
    }

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

    @Test
    void successfulResourceFetch_returnsHtmlWith200() {
        when(request.getParam("uri")).thenReturn("ui://statgpt/chart.html");
        Application app = appWithEndpoint("http://mcp.example.com/mcp");
        when(deploymentService.findDeployment(context, "statgpt")).thenReturn(app);

        mockClientOptions();

        String responseBody = """
                {"jsonrpc":"2.0","id":1,"result":{"contents":[{"mimeType":"text/html","text":"<h1>Chart</h1>"}]}}
                """;
        mockHttpResponse(200, responseBody);

        io.vertx.core.http.HttpServerResponse httpResponse = mock(io.vertx.core.http.HttpServerResponse.class);
        when(context.getResponse()).thenReturn(httpResponse);
        when(httpResponse.putHeader(any(CharSequence.class), any(CharSequence.class))).thenReturn(httpResponse);
        when(httpResponse.putHeader(anyString(), anyString())).thenReturn(httpResponse);
        when(httpResponse.end(anyString())).thenReturn(Future.succeededFuture());

        controller.handle();

        verify(httpResponse).end("<h1>Chart</h1>");
    }

    @Test
    void mcpServerReturnsNon200_returns502() {
        when(request.getParam("uri")).thenReturn("ui://statgpt/chart.html");
        Application app = appWithEndpoint("http://mcp.example.com/mcp");
        when(deploymentService.findDeployment(context, "statgpt")).thenReturn(app);

        mockClientOptions();
        mockHttpResponse(500, "Internal Server Error");

        controller.handle();

        verify(context).respond(HttpStatus.BAD_GATEWAY, "MCP server returned 500");
    }

    @Test
    void emptyContents_returns502() {
        when(request.getParam("uri")).thenReturn("ui://statgpt/chart.html");
        Application app = appWithEndpoint("http://mcp.example.com/mcp");
        when(deploymentService.findDeployment(context, "statgpt")).thenReturn(app);

        mockClientOptions();
        mockHttpResponse(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"contents\":[]}}");

        controller.handle();

        verify(context).respond(HttpStatus.BAD_GATEWAY, "Empty resource contents");
    }

    @Test
    void missingMimeType_returns502() {
        when(request.getParam("uri")).thenReturn("ui://statgpt/chart.html");
        Application app = appWithEndpoint("http://mcp.example.com/mcp");
        when(deploymentService.findDeployment(context, "statgpt")).thenReturn(app);

        mockClientOptions();
        mockHttpResponse(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"contents\":[{\"text\":\"<h1>hi</h1>\"}]}}");

        controller.handle();

        verify(context).respond(HttpStatus.BAD_GATEWAY, "Resource missing mimeType");
    }

    @Test
    void disallowedMimeType_returns502() {
        when(request.getParam("uri")).thenReturn("ui://statgpt/chart.html");
        Application app = appWithEndpoint("http://mcp.example.com/mcp");
        when(deploymentService.findDeployment(context, "statgpt")).thenReturn(app);

        mockClientOptions();
        mockHttpResponse(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"contents\":[{\"mimeType\":\"application/octet-stream\",\"text\":\"bin\"}]}}");

        controller.handle();

        verify(context).respond(HttpStatus.BAD_GATEWAY, "Unsupported resource mimeType: application/octet-stream");
    }

    @Test
    void mimeTypeWithCrlf_returns502() {
        when(request.getParam("uri")).thenReturn("ui://statgpt/chart.html");
        Application app = appWithEndpoint("http://mcp.example.com/mcp");
        when(deploymentService.findDeployment(context, "statgpt")).thenReturn(app);

        mockClientOptions();
        mockHttpResponse(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"contents\":[{\"mimeType\":\"text/html\\r\\nX-Injected: evil\",\"text\":\"<h1>hi</h1>\"}]}}");

        controller.handle();

        verify(context).respond(HttpStatus.BAD_GATEWAY, "Unsupported resource mimeType: text/html\r\nX-Injected: evil");
    }

    // --- helpers ---

    private static Application appWithEndpoint(String endpoint) {
        Application.Mcp mcp = new Application.Mcp();
        mcp.setEndpoint(endpoint);
        mcp.setForwardPerRequestKey(false);
        Application app = new Application();
        app.setMcp(mcp);
        return app;
    }

    private void mockClientOptions() {
        HttpClientOptions opts = mock(HttpClientOptions.class);
        when(opts.getConnectTimeout()).thenReturn(5000);
        when(opts.getIdleTimeout()).thenReturn(30000);
        when(proxy.getClientOptions()).thenReturn(opts);
        when(context.getProxy()).thenReturn(proxy);
    }

    private void mockHttpResponse(int statusCode, String body) {
        HttpClientResponse resp = mock(HttpClientResponse.class);
        when(resp.statusCode()).thenReturn(statusCode);
        when(resp.body()).thenReturn(Future.succeededFuture(Buffer.buffer(body)));

        HttpClientRequest req = mock(HttpClientRequest.class);
        when(req.putHeader(any(CharSequence.class), any(CharSequence.class))).thenReturn(req);
        when(req.send(any(Buffer.class))).thenReturn(Future.succeededFuture(resp));
        when(httpClient.request(any())).thenReturn(Future.succeededFuture(req));
    }
}

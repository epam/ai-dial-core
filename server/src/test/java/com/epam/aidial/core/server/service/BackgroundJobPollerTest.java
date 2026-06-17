package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, VertxExtension.class})
class BackgroundJobPollerTest {

    private static final String DEPLOYMENT_NAME = "test-model";
    private static final String UPSTREAM_KEY = "upstream-key";
    private static final String UPSTREAM_RESPONSE_ID = "upstream-resp-id";

    @Mock
    private HttpClient httpClient;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private HttpClientRequest httpRequest;

    @Mock
    private HttpClientResponse httpResponse;

    @Mock
    private ConfigStore configStore;

    @Mock
    private UpstreamRouteProvider upstreamRouteProvider;

    private BackgroundJobPoller poller;

    @BeforeEach
    void setUp() {
        ResponsesApiClient responsesApiClient = new ResponsesApiClient(httpClient, new HttpClientOptions());
        poller = new BackgroundJobPoller(configStore, upstreamRouteProvider, responsesApiClient);
    }

    @Test
    void pollReturnsUsageForTerminalStatus(VertxTestContext ctx) throws Throwable {
        setupHttpMocks("{\"status\":\"completed\",\"usage\":{}}");
        setupDeploymentMocks();

        poller.poll(buildMapping())
                .onSuccess(usage -> ctx.verify(() -> {
                    assertNotNull(usage);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
        await(ctx);
    }

    @Test
    void pollReturnsNullForNonTerminalStatus(VertxTestContext ctx) throws Throwable {
        setupHttpMocks("{\"status\":\"in_progress\"}");
        setupDeploymentMocks();

        poller.poll(buildMapping())
                .onSuccess(usage -> ctx.verify(() -> {
                    assertNull(usage);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
        await(ctx);
    }

    @Test
    void pollReturnsNullForQueuedStatus(VertxTestContext ctx) throws Throwable {
        setupHttpMocks("{\"status\":\"queued\"}");
        setupDeploymentMocks();

        poller.poll(buildMapping())
                .onSuccess(usage -> ctx.verify(() -> {
                    assertNull(usage);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
        await(ctx);
    }

    @Test
    void pollFailsWhenDeploymentNotFound(VertxTestContext ctx) throws Throwable {
        Config config = mock(Config.class);
        when(configStore.get()).thenReturn(config);
        when(config.selectDeployment(anyString())).thenReturn(null);

        poller.poll(buildMapping())
                .onSuccess(ignored -> ctx.failNow(new AssertionError("Expected failure but got success")))
                .onFailure(error -> ctx.verify(() -> {
                    assertTrue(error.getMessage().contains("not found"));
                    ctx.completeNow();
                }));
        await(ctx);
    }

    @Test
    void pollFailsWhenUpstreamReturnsNonOkStatus(VertxTestContext ctx) throws Throwable {
        setupDeploymentMocks();
        when(httpClient.request(any(RequestOptions.class))).thenReturn(Future.succeededFuture(httpRequest));
        when(httpRequest.putHeader(anyString(), anyString())).thenReturn(httpRequest);
        when(httpRequest.send()).thenReturn(Future.succeededFuture(httpResponse));
        when(httpResponse.statusCode()).thenReturn(500);

        poller.poll(buildMapping())
                .onSuccess(ignored -> ctx.failNow(new AssertionError("Expected failure but got success")))
                .onFailure(error -> ctx.verify(() -> {
                    assertTrue(error.getMessage().contains("500"));
                    ctx.completeNow();
                }));
        await(ctx);
    }

    @Test
    void pollFailsWhenResponseBodyIsNotJson(VertxTestContext ctx) throws Throwable {
        setupDeploymentMocks();
        setupHttpMocks("not valid json {{{}");

        poller.poll(buildMapping())
                .onSuccess(ignored -> ctx.failNow(new AssertionError("Expected failure but got success")))
                .onFailure(error -> ctx.completeNow());
        await(ctx);
    }

    @Test
    void pollFailsWhenResponseBodyIsJsonArray(VertxTestContext ctx) throws Throwable {
        setupDeploymentMocks();
        setupHttpMocks("[1, 2, 3]");

        poller.poll(buildMapping())
                .onSuccess(ignored -> ctx.failNow(new AssertionError("Expected failure but got success")))
                .onFailure(error -> ctx.verify(() -> {
                    assertTrue(error.getMessage().contains("not a JSON object"));
                    ctx.completeNow();
                }));
        await(ctx);
    }

    @Test
    void pollFailsWhenUpstreamRouteNotFound(VertxTestContext ctx) throws Throwable {
        Config config = mock(Config.class);
        Deployment deployment = mock(Deployment.class);
        when(configStore.get()).thenReturn(config);
        when(config.selectDeployment(anyString())).thenReturn(deployment);
        when(deployment.getResponsesEndpoint()).thenReturn("http://test-upstream/responses");
        when(upstreamRouteProvider.get(any(), any(), anyString()))
                .thenThrow(new RuntimeException("No available upstream"));

        poller.poll(buildMapping())
                .onSuccess(ignored -> ctx.failNow(new AssertionError("Expected failure but got success")))
                .onFailure(error -> ctx.verify(() -> {
                    assertTrue(error.getMessage().contains("Failed to get upstream"));
                    ctx.completeNow();
                }));
        await(ctx);
    }

    @Test
    void pollFailsWhenNoResponsesEndpoint(VertxTestContext ctx) throws Throwable {
        Config config = mock(Config.class);
        Deployment deployment = mock(Deployment.class);
        when(configStore.get()).thenReturn(config);
        when(config.selectDeployment(anyString())).thenReturn(deployment);
        when(deployment.getResponsesEndpoint()).thenReturn(null);
        when(deployment.getName()).thenReturn(DEPLOYMENT_NAME);

        poller.poll(buildMapping())
                .onSuccess(ignored -> ctx.failNow(new AssertionError("Expected failure but got success")))
                .onFailure(error -> ctx.verify(() -> {
                    assertTrue(error.getMessage().contains("responses endpoint"));
                    ctx.completeNow();
                }));
        await(ctx);
    }

    private static ResponseMapping buildMapping() {
        return ResponseMapping.builder()
                .upstreamResponseId(UPSTREAM_RESPONSE_ID)
                .upstreamKey(UPSTREAM_KEY)
                .deploymentName(DEPLOYMENT_NAME)
                .initiatorBucket("Users/test-user/")
                .build();
    }

    private void setupDeploymentMocks() {
        Config config = mock(Config.class);
        Deployment deployment = mock(Deployment.class);
        when(configStore.get()).thenReturn(config);
        when(config.selectDeployment(anyString())).thenReturn(deployment);
        when(deployment.getResponsesEndpoint()).thenReturn("http://test-upstream/responses");

        Upstream upstream = mock(Upstream.class);
        when(upstream.getKey()).thenReturn("api-key");
        when(upstream.getResponsesEndpoint()).thenReturn("http://test-upstream");
        when(upstream.getExtraData()).thenReturn("{}");

        UpstreamRoute route = mock(UpstreamRoute.class);
        when(route.next()).thenReturn(upstream);
        when(upstreamRouteProvider.get(any(Deployment.class), isNull(), anyString())).thenReturn(route);
    }

    private void setupHttpMocks(String responseJson) {
        when(httpClient.request(any(RequestOptions.class))).thenReturn(Future.succeededFuture(httpRequest));
        when(httpRequest.putHeader(anyString(), anyString())).thenReturn(httpRequest);
        when(httpRequest.send()).thenReturn(Future.succeededFuture(httpResponse));
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(Future.succeededFuture(Buffer.buffer(responseJson)));
    }

    private static void await(VertxTestContext ctx) throws Throwable {
        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS), "Test timed out");
        if (ctx.failed()) {
            throw ctx.causeOfFailure();
        }
    }
}

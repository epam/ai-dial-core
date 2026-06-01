package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.BackgroundJobRecord;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RFuture;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, VertxExtension.class})
public class BackgroundJobServiceTest {

    private static final String JOB_ID = "test-job-id";
    private static final String UPSTREAM_RESPONSE_ID = "upstream-resp-123";
    private static final String DEPLOYMENT_NAME = "test-model";
    private static final String TRACE_ID = "trace-abc";
    private static final String SPAN_ID = "span-abc";
    private static final String RESPONSES_ENDPOINT = "http://upstream/responses";

    @Mock
    private ResourceService resourceService;
    @Mock
    private ApiKeyStore apiKeyStore;
    @Mock
    private TokenStatsTracker tokenStatsTracker;
    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private ConfigStore configStore;
    @Mock
    private UpstreamRouteProvider upstreamRouteProvider;
    @Mock
    private HttpClient httpClient;
    @Mock
    private LockService lockService;

    private BackgroundJobService service;

    @BeforeEach
    void setUp(Vertx vertx) {
        HttpClientOptions clientOptions = new HttpClientOptions();
        AsyncTaskExecutor taskExecutor = new AsyncTaskExecutor(vertx, new JsonObject(Map.of("useVirtualThreads", false)));
        service = new BackgroundJobService(
                resourceService, apiKeyStore, tokenStatsTracker, rateLimiter,
                configStore, upstreamRouteProvider, httpClient, clientOptions,
                taskExecutor, lockService, () -> "instance-id");
        service.init(vertx);
    }

    @Test
    void testReconnectSseStreamCompletesOnTerminalEvent(Vertx vertx, VertxTestContext testContext) throws Throwable {
        Model deployment = buildDeployment();
        BackgroundJobRecord record = buildRecord();
        BackgroundJobService.Job job = new BackgroundJobService.Job(JOB_ID, record);

        mockLeaseClaimed();
        mockLeaseRelease(testContext);

        when(resourceService.getResource(any())).thenReturn(ProxyUtil.convertToString(record));
        when(resourceService.deleteResource(any(), any())).thenReturn(true);

        Config config = new Config();
        config.setModels(Map.of(DEPLOYMENT_NAME, deployment));
        when(configStore.get()).thenReturn(config);

        Upstream upstream = new Upstream(RESPONSES_ENDPOINT, null, "api-key", null, 1, 0, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(upstreamRouteProvider.get(eq(deployment), any(), nullable(String.class))).thenReturn(upstreamRoute);

        HttpClientRequest httpRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        HttpClientResponse httpResponse = mock(HttpClientResponse.class);
        AtomicReference<Handler<Buffer>> chunkHandlerRef = new AtomicReference<>();
        AtomicReference<Handler<Void>> endHandlerRef = new AtomicReference<>();

        when(httpClient.request(any())).thenReturn(Future.succeededFuture(httpRequest));
        when(httpRequest.send()).thenReturn(Future.succeededFuture(httpResponse));
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("text/event-stream");
        when(httpResponse.handler(any())).thenAnswer(inv -> {
            chunkHandlerRef.set(inv.getArgument(0));
            return httpResponse;
        });
        when(httpResponse.endHandler(any())).thenAnswer(inv -> {
            endHandlerRef.set(inv.getArgument(0));
            return httpResponse;
        });
        when(httpResponse.exceptionHandler(any())).thenReturn(httpResponse);

        when(tokenStatsTracker.updateStats(eq(TRACE_ID), eq(SPAN_ID), any()))
                .thenReturn(Future.succeededFuture());
        when(tokenStatsTracker.endRootSpan(eq(TRACE_ID))).thenReturn(Future.succeededFuture());
        when(rateLimiter.increase(eq(deployment), anyString(), any(), any(), any()))
                .thenReturn(Future.succeededFuture());

        String ssePayload = "event: response.completed\n"
                + "data: {\"status\":\"completed\",\"usage\":{\"completion_tokens\":5,\"prompt_tokens\":5,\"total_tokens\":10}}\n\n";

        service.reconnectSseStream(job);

        vertx.setTimer(200, ignored -> {
            Handler<Buffer> chunkHandler = chunkHandlerRef.get();
            Handler<Void> endHandler = endHandlerRef.get();
            if (chunkHandler != null) {
                chunkHandler.handle(Buffer.buffer(ssePayload));
            }
            if (endHandler != null) {
                endHandler.handle(null);
            }
        });

        await(testContext);

        verify(resourceService).deleteResource(any(), any());
        verify(tokenStatsTracker).updateStats(eq(TRACE_ID), eq(SPAN_ID), any());
        verify(rateLimiter).increase(eq(deployment), anyString(), any(), any(), any());
    }

    @Test
    void testReconnectSkipsWhenLeaseHeld(Vertx vertx, VertxTestContext testContext) throws Throwable {
        BackgroundJobRecord record = buildRecord();
        BackgroundJobService.Job job = new BackgroundJobService.Job(JOB_ID, record);

        RFuture<Long> leaseHeld = mock(RFuture.class);
        when(leaseHeld.toCompletableFuture())
                .thenReturn(CompletableFuture.completedFuture(BackgroundJobService.LEASE_TTL_MS));
        when(lockService.tryClaimOrRenewAsync(anyString(), anyString(), anyLong())).thenReturn(leaseHeld);

        service.reconnectSseStream(job);

        vertx.setTimer(200, ignored -> testContext.completeNow());
        await(testContext);

        verify(httpClient, never()).request(any());
        verify(resourceService, never()).deleteResource(any(), any());
    }

    @Test
    void testReconnectFallsBackToPollingOnHttpError(Vertx vertx, VertxTestContext testContext) throws Throwable {
        Model deployment = buildDeployment();
        BackgroundJobRecord record = buildRecord();
        BackgroundJobService.Job job = new BackgroundJobService.Job(JOB_ID, record);

        mockLeaseClaimed();
        mockLeaseRelease(testContext);

        when(resourceService.getResource(any())).thenReturn(ProxyUtil.convertToString(record));

        Config config = new Config();
        config.setModels(Map.of(DEPLOYMENT_NAME, deployment));
        when(configStore.get()).thenReturn(config);

        Upstream upstream = new Upstream(RESPONSES_ENDPOINT, null, "api-key", null, 1, 0, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(upstreamRouteProvider.get(eq(deployment), any(), nullable(String.class))).thenReturn(upstreamRoute);

        HttpClientRequest httpRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        HttpClientResponse httpResponse = mock(HttpClientResponse.class);
        when(httpClient.request(any())).thenReturn(Future.succeededFuture(httpRequest));
        when(httpRequest.send()).thenReturn(Future.succeededFuture(httpResponse));
        when(httpResponse.statusCode()).thenReturn(404);

        service.reconnectSseStream(job);

        await(testContext);

        verify(resourceService, never()).deleteResource(any(), any());
        verify(tokenStatsTracker, never()).updateStats(anyString(), anyString(), any());
    }

    @Test
    void testReconnectFallsBackToPollingOnSseStreamError(Vertx vertx, VertxTestContext testContext) throws Throwable {
        Model deployment = buildDeployment();
        BackgroundJobRecord record = buildRecord();
        BackgroundJobService.Job job = new BackgroundJobService.Job(JOB_ID, record);

        mockLeaseClaimed();
        mockLeaseRelease(testContext);

        when(resourceService.getResource(any())).thenReturn(ProxyUtil.convertToString(record));

        Config config = new Config();
        config.setModels(Map.of(DEPLOYMENT_NAME, deployment));
        when(configStore.get()).thenReturn(config);

        Upstream upstream = new Upstream(RESPONSES_ENDPOINT, null, "api-key", null, 1, 0, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(upstreamRouteProvider.get(eq(deployment), any(), nullable(String.class))).thenReturn(upstreamRoute);

        HttpClientRequest httpRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        HttpClientResponse httpResponse = mock(HttpClientResponse.class);
        AtomicReference<Handler<Throwable>> exceptionHandlerRef = new AtomicReference<>();

        when(httpClient.request(any())).thenReturn(Future.succeededFuture(httpRequest));
        when(httpRequest.send()).thenReturn(Future.succeededFuture(httpResponse));
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("text/event-stream");
        when(httpResponse.handler(any())).thenReturn(httpResponse);
        when(httpResponse.endHandler(any())).thenReturn(httpResponse);
        when(httpResponse.exceptionHandler(any())).thenAnswer(inv -> {
            exceptionHandlerRef.set(inv.getArgument(0));
            return httpResponse;
        });

        service.reconnectSseStream(job);

        vertx.setTimer(200, ignored -> {
            Handler<Throwable> exceptionHandler = exceptionHandlerRef.get();
            if (exceptionHandler != null) {
                exceptionHandler.handle(new RuntimeException("connection lost"));
            }
        });

        await(testContext);

        verify(resourceService, never()).deleteResource(any(), any());
        verify(tokenStatsTracker, never()).updateStats(anyString(), anyString(), any());
    }

    @Test
    void testReconnectCompletesFromJsonResponse(Vertx vertx, VertxTestContext testContext) throws Throwable {
        Model deployment = buildDeployment();
        BackgroundJobRecord record = buildRecord();
        BackgroundJobService.Job job = new BackgroundJobService.Job(JOB_ID, record);

        mockLeaseClaimed();
        mockLeaseRelease(testContext);

        when(resourceService.getResource(any())).thenReturn(ProxyUtil.convertToString(record));
        when(resourceService.deleteResource(any(), any())).thenReturn(true);

        Config config = new Config();
        config.setModels(Map.of(DEPLOYMENT_NAME, deployment));
        when(configStore.get()).thenReturn(config);

        Upstream upstream = new Upstream(RESPONSES_ENDPOINT, null, "api-key", null, 1, 0, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(upstreamRouteProvider.get(eq(deployment), any(), nullable(String.class))).thenReturn(upstreamRoute);

        String jsonBody = "{\"id\":\"" + UPSTREAM_RESPONSE_ID + "\",\"status\":\"completed\","
                + "\"usage\":{\"completion_tokens\":5,\"prompt_tokens\":5,\"total_tokens\":10}}";
        HttpClientRequest httpRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        HttpClientResponse httpResponse = mock(HttpClientResponse.class);
        when(httpClient.request(any())).thenReturn(Future.succeededFuture(httpRequest));
        when(httpRequest.send()).thenReturn(Future.succeededFuture(httpResponse));
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("application/json");
        when(httpResponse.body()).thenReturn(Future.succeededFuture(Buffer.buffer(jsonBody)));

        when(tokenStatsTracker.updateStats(eq(TRACE_ID), eq(SPAN_ID), any()))
                .thenReturn(Future.succeededFuture());
        when(tokenStatsTracker.endRootSpan(eq(TRACE_ID))).thenReturn(Future.succeededFuture());
        when(rateLimiter.increase(eq(deployment), anyString(), any(), any(), any()))
                .thenReturn(Future.succeededFuture());

        service.reconnectSseStream(job);

        await(testContext);

        verify(resourceService).deleteResource(any(), any());
        verify(tokenStatsTracker).updateStats(eq(TRACE_ID), eq(SPAN_ID), any());
    }

    @Test
    void testReconnectCleansUpExpiredJob(Vertx vertx, VertxTestContext testContext) throws Throwable {
        BackgroundJobRecord record = BackgroundJobRecord.builder()
                .dialResponseId("dial-resp-123")
                .mapping(buildMapping())
                .traceId(TRACE_ID)
                .spanId(SPAN_ID)
                .createdAt(0L)
                .streaming(true)
                .build();
        BackgroundJobService.Job job = new BackgroundJobService.Job(JOB_ID, record);

        when(resourceService.deleteResource(any(), any())).thenReturn(true);
        mockLeaseRelease(testContext);

        service.reconnectSseStream(job);

        await(testContext);

        verify(resourceService).deleteResource(any(), any());
        verify(httpClient, never()).request(any());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Model buildDeployment() {
        Model deployment = new Model();
        deployment.setName(DEPLOYMENT_NAME);
        deployment.setResponsesEndpoint(RESPONSES_ENDPOINT);
        return deployment;
    }

    private BackgroundJobRecord buildRecord() {
        return BackgroundJobRecord.builder()
                .dialResponseId("dial-resp-123")
                .mapping(buildMapping())
                .traceId(TRACE_ID)
                .spanId(SPAN_ID)
                .createdAt(System.currentTimeMillis())
                .streaming(true)
                .build();
    }

    private ResponseMapping buildMapping() {
        return ResponseMapping.builder()
                .upstreamResponseId(UPSTREAM_RESPONSE_ID)
                .upstreamKey(null)
                .deploymentName(DEPLOYMENT_NAME)
                .initiatorBucket("bucket/location")
                .build();
    }

    private void mockLeaseClaimed() {
        RFuture<Long> claimed = mock(RFuture.class);
        when(claimed.toCompletableFuture()).thenReturn(CompletableFuture.completedFuture(0L));
        when(lockService.tryClaimOrRenewAsync(anyString(), anyString(), anyLong())).thenReturn(claimed);
    }

    private void mockLeaseRelease(VertxTestContext testContext) {
        RFuture<Boolean> released = mock(RFuture.class);
        when(released.toCompletableFuture()).thenReturn(CompletableFuture.completedFuture(true));
        when(lockService.releaseClaimAsync(anyString(), anyString())).thenAnswer(inv -> {
            testContext.completeNow();
            return released;
        });
    }

    private static void await(VertxTestContext testContext) throws Throwable {
        testContext.awaitCompletion(10, TimeUnit.SECONDS);
        if (testContext.failed()) {
            throw testContext.causeOfFailure();
        }
    }
}

package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.tracing.TracingSettings;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.epam.aidial.core.server.controller.ResponseItemController.Operation.CANCEL;
import static com.epam.aidial.core.server.controller.ResponseItemController.Operation.DELETE;
import static com.epam.aidial.core.server.controller.ResponseItemController.Operation.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, VertxExtension.class})
public class ResponseItemControllerTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @Mock
    private HttpServerResponse response;

    @Mock
    private HttpServerRequest serverRequest;

    @BeforeEach
    void stubConfig() {
        // the controller resolves translator references against the request's config on every routing step
        lenient().when(context.getConfig()).thenReturn(new Config());
    }

    private ResponseItemController controller(String dialId, ResponseItemController.Operation op) {
        return new ResponseItemController(proxy, context, dialId, op);
    }

    /**
     * Wires the mocked {@code context} so the four latency timestamps round-trip through
     * {@link AtomicLong}-backed getter/setter pairs - plain mock fields aren't volatile, and the
     * controller sets/reads them from different {@code AsyncTaskExecutor} threads - and
     * {@code getTracingAttributes()} returns an inspectable map, as if {@code genAiSpanAttributes} were on.
     */
    private Map<String, Object> enableLatencyTracing() {
        Map<String, Object> tracingAttributes = new ConcurrentHashMap<>();
        lenient().when(context.getRequestTimestamp()).thenReturn(1000L);
        AtomicLong requestBodyTimestamp = new AtomicLong();
        AtomicLong proxyConnectTimestamp = new AtomicLong();
        AtomicLong proxyResponseTimestamp = new AtomicLong();
        AtomicLong responseBodyTimestamp = new AtomicLong();
        lenient().when(context.getTracingSettings()).thenReturn(new TracingSettings(true, false, List.of()));
        lenient().when(context.getTracingAttributes()).thenReturn(tracingAttributes);
        lenient().doAnswer(inv -> {
            requestBodyTimestamp.set(inv.getArgument(0));
            return null;
        }).when(context).setRequestBodyTimestamp(anyLong());
        lenient().when(context.getRequestBodyTimestamp()).thenAnswer(inv -> requestBodyTimestamp.get());
        lenient().doAnswer(inv -> {
            proxyConnectTimestamp.set(inv.getArgument(0));
            return null;
        }).when(context).setProxyConnectTimestamp(anyLong());
        lenient().when(context.getProxyConnectTimestamp()).thenAnswer(inv -> proxyConnectTimestamp.get());
        lenient().doAnswer(inv -> {
            proxyResponseTimestamp.set(inv.getArgument(0));
            return null;
        }).when(context).setProxyResponseTimestamp(anyLong());
        lenient().when(context.getProxyResponseTimestamp()).thenAnswer(inv -> proxyResponseTimestamp.get());
        lenient().doAnswer(inv -> {
            responseBodyTimestamp.set(inv.getArgument(0));
            return null;
        }).when(context).setResponseBodyTimestamp(anyLong());
        lenient().when(context.getResponseBodyTimestamp()).thenAnswer(inv -> responseBodyTimestamp.get());
        return tracingAttributes;
    }

    private static void assertLatencyPublished(Map<String, Object> tracingAttributes) {
        assertNotNull(tracingAttributes.get("dial.latency.upstream_connect_ms"));
        assertNotNull(tracingAttributes.get("dial.latency.upstream_header_ms"));
        assertNotNull(tracingAttributes.get("dial.latency.upstream_body_ms"));
    }

    @Test
    public void testAccessDenied(Vertx vertx, VertxTestContext testContext) throws Throwable {
        ResponseMapping mapping = ResponseMapping.builder()
                .upstreamResponseId("upstream-id-forbidden")
                .upstreamKey("endpoint")
                .deploymentName("test-deployment")
                .initiatorBucket("Users/other-user/")
                .build();

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        when(context.getUserId()).thenReturn("test-user");
        when(context.getResponse()).thenReturn(response);
        when(response.ended()).thenReturn(false);
        when(context.respond(any(Throwable.class), anyString())).thenAnswer(invocation -> complete(testContext));

        controller("dial_test-deployment_forbidden", GET).handle();

        await(testContext);

        verify(context).respond(
                argThat((Throwable e) -> e instanceof HttpException
                        && ((HttpException) e).getStatus() == HttpStatus.FORBIDDEN
                        && "Access denied".equals(e.getMessage())),
                anyString());
    }

    @Test
    public void testMappingNotFound(Vertx vertx, VertxTestContext testContext) throws Throwable {
        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(null);
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        when(context.getResponse()).thenReturn(response);
        when(response.ended()).thenReturn(false);
        when(context.respond(any(Throwable.class), anyString())).thenAnswer(invocation -> complete(testContext));

        controller("dial_test-deployment_unknown", GET).handle();

        await(testContext);

        verify(context).respond(
                argThat((Throwable e) -> e instanceof HttpException
                        && ((HttpException) e).getStatus() == HttpStatus.NOT_FOUND
                        && e.getMessage().contains("Response with id 'dial_test-deployment_unknown' not found.")),
                anyString());
    }

    @Test
    public void testGetForwardsToUpstream(Vertx vertx, VertxTestContext testContext) throws Throwable {
        ResponseMapping mapping = ResponseMapping.builder()
                .upstreamResponseId("upstream-id-123")
                .upstreamKey("endpoint")
                .deploymentName("test-deployment")
                .initiatorBucket("Users/test-user/")
                .build();
        Model deployment = new Model();
        deployment.setName("test-deployment");
        deployment.setResponsesEndpoint("http://adapter/responses");
        Upstream upstream = new Upstream(null, "endpoint", "api-key", null, null, 0, 0, null, null, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);
        Buffer responseBody = Buffer.buffer("{\"id\":\"upstream-id-123\",\"status\":\"completed\"}");

        Map<String, Object> tracingAttributes = enableLatencyTracing();

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), eq("endpoint"))).thenReturn(upstreamRoute);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(proxy.getResponsesApiClient().send(anyString(), any(HttpMethod.class), any(Upstream.class), any(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(4)).run();
                    return Future.succeededFuture(proxyResponse);
                });
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(responseBody));
        when(proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("application/json");
        when(context.getResponse()).thenReturn(response);
        when(context.getRequest()).thenReturn(serverRequest);
        when(context.getUserId()).thenReturn("test-user");
        when(serverRequest.headers()).thenReturn(new HeadersMultiMap());
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(response.setStatusCode(200)).thenReturn(response);
        when(response.putHeader(any(CharSequence.class), anyString())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));

        controller("dial_test-deployment_123", GET).handle().onComplete(ar -> testContext.completeNow());

        await(testContext);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpMethod> methodCaptor = ArgumentCaptor.forClass(HttpMethod.class);
        verify(proxy.getResponsesApiClient()).send(urlCaptor.capture(), methodCaptor.capture(), any(Upstream.class), any(), any(Runnable.class));
        assertEquals("http://adapter/responses/upstream-id-123", urlCaptor.getValue());
        assertEquals(HttpMethod.GET, methodCaptor.getValue());

        ArgumentCaptor<Buffer> bodyCaptor = ArgumentCaptor.forClass(Buffer.class);
        verify(response).end(bodyCaptor.capture());
        JsonNode sentJson = ProxyUtil.MAPPER.readTree(bodyCaptor.getValue().getBytes());
        assertEquals("dial_test-deployment_123", sentJson.path("id").asText());

        verify(proxy.getResponseMappingService(), never()).deleteMapping(anyString());
        assertLatencyPublished(tracingAttributes);
    }

    @Test
    public void testGetForwardsToUpstreamWithInterfaces(Vertx vertx, VertxTestContext testContext) throws Throwable {
        ResponseMapping mapping = ResponseMapping.builder()
                .upstreamResponseId("upstream-id-123")
                .upstreamKey("endpoint")
                .deploymentName("test-deployment")
                .initiatorBucket("Users/test-user/")
                .build();
        Model deployment = new Model();
        deployment.setName("test-deployment");
        deployment.setInterfaces(Map.of(
                InterfaceType.OPENAI_RESPONSES.getValue(), new DeploymentInterface("http://adapter")));
        Upstream upstream = new Upstream(null, "endpoint", "api-key", null, null, 0, 0, null, null, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);
        Buffer responseBody = Buffer.buffer("{\"id\":\"upstream-id-123\",\"status\":\"completed\"}");

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), eq("endpoint"))).thenReturn(upstreamRoute);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(proxy.getResponsesApiClient().send(anyString(), any(HttpMethod.class), any(Upstream.class), any(), any(Runnable.class)))
                .thenReturn(Future.succeededFuture(proxyResponse));
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(responseBody));
        when(proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("application/json");
        when(context.getResponse()).thenReturn(response);
        when(context.getRequest()).thenReturn(serverRequest);
        when(context.getUserId()).thenReturn("test-user");
        when(serverRequest.headers()).thenReturn(new HeadersMultiMap());
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(response.setStatusCode(200)).thenReturn(response);
        when(response.putHeader(any(CharSequence.class), anyString())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenAnswer(invocation -> complete(testContext));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));

        controller("dial_test-deployment_123", GET).handle();

        await(testContext);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<HttpMethod> methodCaptor = ArgumentCaptor.captor();
        verify(proxy.getResponsesApiClient()).send(urlCaptor.capture(), methodCaptor.capture(), any(Upstream.class), any(), any(Runnable.class));
        assertEquals("http://adapter/openai/v1/responses/upstream-id-123", urlCaptor.getValue());
        assertEquals(HttpMethod.GET, methodCaptor.getValue());
    }

    @Test
    public void testCancelForwardsToUpstream(Vertx vertx, VertxTestContext testContext) throws Throwable {
        ResponseMapping mapping = ResponseMapping.builder()
                .upstreamResponseId("upstream-id-123")
                .upstreamKey("endpoint")
                .deploymentName("test-deployment")
                .initiatorBucket("Users/test-user/")
                .build();
        Model deployment = new Model();
        deployment.setName("test-deployment");
        deployment.setResponsesEndpoint("http://adapter/responses");
        Upstream upstream = new Upstream(null, "endpoint", "api-key", null, null, 0, 0, null, null, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);
        Buffer responseBody = Buffer.buffer("{\"id\":\"upstream-id-123\",\"status\":\"cancelled\"}");

        Map<String, Object> tracingAttributes = enableLatencyTracing();

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), eq("endpoint"))).thenReturn(upstreamRoute);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(proxy.getResponsesApiClient().send(anyString(), any(HttpMethod.class), any(Upstream.class), any(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(4)).run();
                    return Future.succeededFuture(proxyResponse);
                });
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(responseBody));
        when(proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("application/json");
        when(context.getResponse()).thenReturn(response);
        when(context.getRequest()).thenReturn(serverRequest);
        when(context.getUserId()).thenReturn("test-user");
        when(serverRequest.headers()).thenReturn(new HeadersMultiMap());
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(response.setStatusCode(200)).thenReturn(response);
        when(response.putHeader(any(CharSequence.class), anyString())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));

        controller("dial_test-deployment_123", CANCEL).handle().onComplete(ar -> testContext.completeNow());

        await(testContext);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpMethod> methodCaptor = ArgumentCaptor.forClass(HttpMethod.class);
        verify(proxy.getResponsesApiClient()).send(urlCaptor.capture(), methodCaptor.capture(), any(Upstream.class), any(), any(Runnable.class));
        assertEquals("http://adapter/responses/upstream-id-123/cancel", urlCaptor.getValue());
        assertEquals(HttpMethod.POST, methodCaptor.getValue());
        assertLatencyPublished(tracingAttributes);
    }

    @Test
    public void testDeleteDeletesMappingOn200(Vertx vertx, VertxTestContext testContext) throws Throwable {
        ResponseMapping mapping = ResponseMapping.builder()
                .upstreamResponseId("upstream-id-del")
                .upstreamKey("endpoint")
                .deploymentName("test-deployment")
                .initiatorBucket("Users/test-user/")
                .build();
        Model deployment = new Model();
        deployment.setName("test-deployment");
        deployment.setResponsesEndpoint("http://adapter/responses");
        Upstream upstream = new Upstream(null, "endpoint", "api-key", null, null, 0, 0, null, null, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);

        Map<String, Object> tracingAttributes = enableLatencyTracing();

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getBackgroundJobService().isJobActive(anyString())).thenReturn(Future.succeededFuture(false));
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), eq("endpoint"))).thenReturn(upstreamRoute);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(proxy.getResponsesApiClient().send(anyString(), any(HttpMethod.class), any(Upstream.class), any(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(4)).run();
                    return Future.succeededFuture(proxyResponse);
                });
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(Buffer.buffer("")));
        when(proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(null);
        when(context.getResponse()).thenReturn(response);
        when(context.getRequest()).thenReturn(serverRequest);
        when(context.getUserId()).thenReturn("test-user");
        when(serverRequest.headers()).thenReturn(new HeadersMultiMap());
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(response.setStatusCode(200)).thenReturn(response);
        when(response.putHeader(any(CharSequence.class), anyString())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));

        controller("dial_test-deployment_del", DELETE).handle().onComplete(ar -> testContext.completeNow());

        await(testContext);

        verify(proxy.getResponseMappingService()).deleteMapping(anyString());
        assertLatencyPublished(tracingAttributes);
    }

    @Test
    public void testDeleteKeepsMappingOnNon200(Vertx vertx, VertxTestContext testContext) throws Throwable {
        ResponseMapping mapping = ResponseMapping.builder()
                .upstreamResponseId("upstream-id-del")
                .upstreamKey("endpoint")
                .deploymentName("test-deployment")
                .initiatorBucket("Users/test-user/")
                .build();
        Model deployment = new Model();
        deployment.setName("test-deployment");
        deployment.setResponsesEndpoint("http://adapter/responses");
        Upstream upstream = new Upstream(null, "endpoint", "api-key", null, null, 0, 0, null, null, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getBackgroundJobService().isJobActive(anyString())).thenReturn(Future.succeededFuture(false));
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), eq("endpoint"))).thenReturn(upstreamRoute);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(proxy.getResponsesApiClient().send(anyString(), any(HttpMethod.class), any(Upstream.class), any(), any(Runnable.class)))
                .thenReturn(Future.succeededFuture(proxyResponse));
        when(proxyResponse.statusCode()).thenReturn(400);
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(Buffer.buffer("{\"id\":\"upstream-id-del\"}")));
        when(proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("application/json");
        when(context.getResponse()).thenReturn(response);
        when(context.getRequest()).thenReturn(serverRequest);
        when(context.getUserId()).thenReturn("test-user");
        when(serverRequest.headers()).thenReturn(new HeadersMultiMap());
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(response.setStatusCode(400)).thenReturn(response);
        when(response.putHeader(any(CharSequence.class), anyString())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenAnswer(invocation -> complete(testContext));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));

        controller("dial_test-deployment_del", DELETE).handle();

        await(testContext);

        verify(proxy.getResponseMappingService(), never()).deleteMapping(anyString());
    }

    @Test
    public void testDeleteBlockedByActiveBackgroundJob(Vertx vertx, VertxTestContext testContext) throws Throwable {
        ResponseMapping mapping = ResponseMapping.builder()
                .upstreamResponseId("upstream-id-del")
                .upstreamKey("endpoint")
                .deploymentName("test-deployment")
                .initiatorBucket("Users/test-user/")
                .build();

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getBackgroundJobService().isJobActive(anyString())).thenReturn(Future.succeededFuture(true));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        when(context.getUserId()).thenReturn("test-user");
        when(context.getResponse()).thenReturn(response);
        when(response.ended()).thenReturn(false);
        when(context.respond(any(Throwable.class), anyString())).thenAnswer(invocation -> complete(testContext));

        controller("dial_test-deployment_del", DELETE).handle();

        await(testContext);

        verify(context).respond(
                argThat((Throwable e) -> e instanceof HttpException
                        && ((HttpException) e).getStatus() == HttpStatus.CONFLICT
                        && "Cannot delete response while background job is in progress".equals(e.getMessage())),
                anyString());
        verify(proxy.getResponseMappingService(), never()).deleteMapping(anyString());
    }

    @Test
    public void testNoResponsesEndpoint(Vertx vertx, VertxTestContext testContext) throws Throwable {
        ResponseMapping mapping = ResponseMapping.builder()
                .upstreamResponseId("upstream-id")
                .upstreamKey("endpoint")
                .deploymentName("no-responses-deployment")
                .initiatorBucket("Users/test-user/")
                .build();
        Model deployment = new Model();
        deployment.setName("no-responses-deployment");

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getDeploymentService().findDeployment(context, "no-responses-deployment")).thenReturn(deployment);
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        when(context.getUserId()).thenReturn("test-user");
        when(context.respond(any(HttpStatus.class), anyString())).thenAnswer(invocation -> complete(testContext));

        controller("dial_no-responses-deployment_x", GET).handle();

        await(testContext);

        verify(context).respond(HttpStatus.SERVICE_UNAVAILABLE,
                "Deployment for response_id does not support Responses API");
    }

    @Test
    public void testUpstreamNotFound(Vertx vertx, VertxTestContext testContext) throws Throwable {
        ResponseMapping mapping = ResponseMapping.builder()
                .upstreamResponseId("upstream-id")
                .upstreamKey("missing-upstream-key")
                .deploymentName("test-deployment")
                .initiatorBucket("Users/test-user/")
                .build();
        Model deployment = new Model();
        deployment.setName("test-deployment");
        deployment.setResponsesEndpoint("http://adapter/responses");
        // this short-circuit never reaches an upstream, so only the client_body_ms phase can have
        // happened - the other three timestamps are never set on this path
        Map<String, Object> tracingAttributes = enableLatencyTracing();

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), eq("missing-upstream-key")))
                .thenThrow(new HttpException(HttpStatus.BAD_REQUEST, "Unknown upstream id missing-upstream-key"));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        when(context.getUserId()).thenReturn("test-user");
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getResponse()).thenReturn(response);
        when(response.ended()).thenReturn(false);
        when(context.respond(any(Throwable.class), anyString())).thenAnswer(invocation -> complete(testContext));

        controller("dial_test-deployment_y", GET).handle();

        await(testContext);

        verify(context).respond(
                argThat((Throwable e) -> e instanceof HttpException
                        && ((HttpException) e).getStatus() == HttpStatus.BAD_REQUEST
                        && "Unknown upstream id missing-upstream-key".equals(e.getMessage())),
                anyString());
        assertNotNull(tracingAttributes.get("dial.latency.client_body_ms"));
    }

    @Test
    public void testEmptyBodySkipsRewrite(Vertx vertx, VertxTestContext testContext) throws Throwable {
        ResponseMapping mapping = ResponseMapping.builder()
                .upstreamResponseId("upstream-id-empty")
                .upstreamKey("endpoint")
                .deploymentName("test-deployment")
                .initiatorBucket("Users/test-user/")
                .build();
        Model deployment = new Model();
        deployment.setName("test-deployment");
        deployment.setResponsesEndpoint("http://adapter/responses");
        Upstream upstream = new Upstream(null, "endpoint", "api-key", null, null, 0, 0, null, null, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);

        Map<String, Object> tracingAttributes = enableLatencyTracing();

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), eq("endpoint"))).thenReturn(upstreamRoute);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(proxy.getResponsesApiClient().send(anyString(), any(HttpMethod.class), any(Upstream.class), any(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(4)).run();
                    return Future.succeededFuture(proxyResponse);
                });
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(Buffer.buffer("")));
        when(proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(null);
        when(context.getResponse()).thenReturn(response);
        when(context.getRequest()).thenReturn(serverRequest);
        when(context.getUserId()).thenReturn("test-user");
        when(serverRequest.headers()).thenReturn(new HeadersMultiMap());
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(response.setStatusCode(200)).thenReturn(response);
        when(response.putHeader(any(CharSequence.class), anyString())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));

        controller("dial_test-deployment_empty", GET).handle().onComplete(ar -> testContext.completeNow());

        await(testContext);

        ArgumentCaptor<Buffer> bodyCaptor = ArgumentCaptor.forClass(Buffer.class);
        verify(response).end(bodyCaptor.capture());
        assertEquals(0, bodyCaptor.getValue().length());
        verify(proxy.getResponseMappingService(), never()).deleteMapping(anyString());
        assertLatencyPublished(tracingAttributes);
    }

    @Test
    public void testGetStreamingForwardsSseWithRewrittenId(Vertx vertx, VertxTestContext testContext) throws Throwable {
        ResponseMapping mapping = ResponseMapping.builder()
                .upstreamResponseId("upstream-id-stream")
                .upstreamKey("endpoint")
                .deploymentName("test-deployment")
                .initiatorBucket("Users/test-user/")
                .build();
        Model deployment = new Model();
        deployment.setName("test-deployment");
        deployment.setResponsesEndpoint("http://adapter/responses");
        Upstream upstream = new Upstream(null, "endpoint", "api-key", null, null, 0, 0, null, null, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);

        String upstreamId = "upstream-id-stream";
        String sseContent = "event: response.created\n"
                + "data: {\"response\":{\"id\":\"" + upstreamId + "\"}}\n\n"
                + "event: response.completed\n"
                + "data: {\"response\":{\"id\":\"" + upstreamId + "\"}}\n\n";

        AtomicReference<Handler<Buffer>> chunkHandlerRef = new AtomicReference<>();
        AtomicReference<Handler<Void>> endHandlerRef = new AtomicReference<>();
        List<Buffer> writtenChunks = new ArrayList<>();
        AtomicReference<Buffer> endChunkRef = new AtomicReference<>();

        Map<String, Object> tracingAttributes = enableLatencyTracing();

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), eq("endpoint"))).thenReturn(upstreamRoute);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(proxy.getResponsesApiClient().send(anyString(), any(HttpMethod.class), any(Upstream.class), any(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(4)).run();
                    return Future.succeededFuture(proxyResponse);
                });
        when(context.getRequest()).thenReturn(serverRequest);
        when(serverRequest.query()).thenReturn("stream=true");
        when(serverRequest.headers()).thenReturn(new HeadersMultiMap());
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("text/event-stream");
        when(proxyResponse.headers()).thenReturn(new HeadersMultiMap());
        when(proxyResponse.pause()).thenReturn(proxyResponse);
        when(proxyResponse.exceptionHandler(any())).thenReturn(proxyResponse);
        when(proxyResponse.handler(any())).thenAnswer(inv -> {
            chunkHandlerRef.set(inv.getArgument(0));
            return proxyResponse;
        });
        when(proxyResponse.endHandler(any())).thenAnswer(inv -> {
            endHandlerRef.set(inv.getArgument(0));
            return proxyResponse;
        });
        when(proxyResponse.fetch(anyLong())).thenAnswer(inv -> {
            chunkHandlerRef.get().handle(Buffer.buffer(sseContent));
            endHandlerRef.get().handle(null);
            return proxyResponse;
        });

        when(context.getResponse()).thenReturn(response);
        when(context.getUserId()).thenReturn("test-user");
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(response.setChunked(anyBoolean())).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        when(response.headers()).thenReturn(new HeadersMultiMap());
        doAnswer(inv -> {
            writtenChunks.add(inv.getArgument(0));
            return response;
        }).when(response).write(any(Buffer.class), any());
        doAnswer(inv -> {
            endChunkRef.set(inv.getArgument(0));
            return Future.succeededFuture();
        }).when(response).end(any(Buffer.class));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));

        controller("dial_test-deployment_stream", GET).handle().onComplete(ar -> testContext.completeNow());

        await(testContext);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(proxy.getResponsesApiClient()).send(urlCaptor.capture(), any(HttpMethod.class), any(Upstream.class), any(), any(Runnable.class));
        assertEquals("http://adapter/responses/upstream-id-stream?stream=true", urlCaptor.getValue());

        // First event (response.created) forwarded as a regular chunk with rewritten id
        assertEquals(1, writtenChunks.size());
        String firstEvent = writtenChunks.get(0).toString();
        assertTrue(firstEvent.contains("dial_test-deployment_stream"));
        assertFalse(firstEvent.contains(upstreamId));

        // Last event (response.completed) sent via end() with rewritten id
        assertNotNull(endChunkRef.get());
        String lastEvent = endChunkRef.get().toString();
        assertTrue(lastEvent.contains("dial_test-deployment_stream"));
        assertFalse(lastEvent.contains(upstreamId));

        assertLatencyPublished(tracingAttributes);
    }

    /**
     * {@code collectAndForwardStreaming()}'s pipe-failure branch
     * ({@code response.reset()} on a client disconnect mid-stream) publishes {@code dial.latency.*}
     * onto the still-recording span before {@code reset()}, matching every sibling disconnect path in
     * the codebase (its own {@code onSuccess} branch three lines above, {@code
     * BaseDeploymentPostController.handleResponseError()}, {@code
     * BaseInterceptorController.handleResponseError()}). Since Vert.x ends the OTel span
     * synchronously inside {@code reset()}, publishing after it would be a no-op on the span - so the
     * ordering, not just eventual presence in the tracing-attributes map, is what this test checks.
     */
    @Test
    public void testGetStreamingClientDisconnect_PublishesLatencyAttributesBeforeReset(Vertx vertx, VertxTestContext testContext) throws Throwable {
        ResponseMapping mapping = ResponseMapping.builder()
                .upstreamResponseId("upstream-id-stream")
                .upstreamKey("endpoint")
                .deploymentName("test-deployment")
                .initiatorBucket("Users/test-user/")
                .build();
        Model deployment = new Model();
        deployment.setName("test-deployment");
        deployment.setResponsesEndpoint("http://adapter/responses");
        Upstream upstream = new Upstream(null, "endpoint", "api-key", null, null, 0, 0, null, null, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);

        String upstreamId = "upstream-id-stream";
        String sseContent = "event: response.created\n"
                + "data: {\"response\":{\"id\":\"" + upstreamId + "\"}}\n\n";

        AtomicReference<Handler<Buffer>> chunkHandlerRef = new AtomicReference<>();
        AtomicReference<Handler<Void>> endHandlerRef = new AtomicReference<>();

        Map<String, Object> tracingAttributes = enableLatencyTracing();

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), eq("endpoint"))).thenReturn(upstreamRoute);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(proxy.getResponsesApiClient().send(anyString(), any(HttpMethod.class), any(Upstream.class), any(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(4)).run();
                    return Future.succeededFuture(proxyResponse);
                });
        when(context.getRequest()).thenReturn(serverRequest);
        when(serverRequest.query()).thenReturn("stream=true");
        when(serverRequest.headers()).thenReturn(new HeadersMultiMap());
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("text/event-stream");
        when(proxyResponse.headers()).thenReturn(new HeadersMultiMap());
        when(proxyResponse.pause()).thenReturn(proxyResponse);
        when(proxyResponse.exceptionHandler(any())).thenReturn(proxyResponse);
        when(proxyResponse.handler(any())).thenAnswer(inv -> {
            chunkHandlerRef.set(inv.getArgument(0));
            return proxyResponse;
        });
        when(proxyResponse.endHandler(any())).thenAnswer(inv -> {
            endHandlerRef.set(inv.getArgument(0));
            return proxyResponse;
        });
        when(proxyResponse.fetch(anyLong())).thenAnswer(inv -> {
            chunkHandlerRef.get().handle(Buffer.buffer(sseContent));
            // the client goes away before the stream completes - no endHandler firing
            return proxyResponse;
        });

        when(context.getResponse()).thenReturn(response);
        when(context.getUserId()).thenReturn("test-user");
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(response.setChunked(anyBoolean())).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        when(response.headers()).thenReturn(new HeadersMultiMap());
        // simulate a broken client connection: the write to the client fails
        RuntimeException writeFailure = new RuntimeException("connection reset by peer");
        doAnswer(inv -> {
            Handler<AsyncResult<Void>> handler = inv.getArgument(1);
            handler.handle(Future.failedFuture(writeFailure));
            return response;
        }).when(response).write(any(Buffer.class), any());
        // captures whether latency attributes were already published at the exact moment reset() runs -
        // presence in the map alone isn't enough proof, since handle()'s top-level .onFailure() safety
        // net would also publish them, redundantly, after reset() if this branch didn't already
        AtomicReference<Boolean> latencyPublishedBeforeReset = new AtomicReference<>();
        doAnswer(inv -> {
            latencyPublishedBeforeReset.set(tracingAttributes.containsKey("dial.latency.client_body_ms"));
            return null;
        }).when(response).reset();
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));

        controller("dial_test-deployment_stream", GET).handle().onComplete(ar -> testContext.completeNow());

        await(testContext);

        // the failure path did run and did reset the connection...
        verify(response).reset();
        // ...and dial.latency.* was already published at that moment - i.e. before the OTel span was
        // ended by reset(), not afterward via handle()'s top-level .onFailure() safety net (too late).
        assertTrue(latencyPublishedBeforeReset.get());
        assertNotNull(tracingAttributes.get("dial.latency.client_body_ms"));
    }

    @Test
    public void testInterceptorReentryDispatchesToNextInterceptor(Vertx vertx, VertxTestContext testContext) throws Throwable {
        ResponseMapping mapping = ResponseMapping.builder()
                .upstreamResponseId("upstream-id-123")
                .upstreamKey("endpoint")
                .deploymentName("test-deployment")
                .initiatorBucket("Users/test-user/")
                .build();
        Model deployment = new Model();
        deployment.setName("test-deployment");
        deployment.setResponsesEndpoint("http://adapter/responses");

        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("per-request-key");
        apiKeyData.setInterceptors(List.of("interceptor1", "interceptor2"));
        apiKeyData.setInterceptorIndex(0);
        apiKeyData.setInitialDeployment("test-model");
        apiKeyData.setExecutionPath(List.of());

        Interceptor interceptor2 = new Interceptor();
        interceptor2.setResponsesEndpoint("http://interceptor2/responses");

        Config config = new Config();
        config.setInterceptors(Map.of("interceptor1", new Interceptor(), "interceptor2", interceptor2));

        HttpClient httpClient = mock(HttpClient.class, RETURNS_DEEP_STUBS);

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        when(proxy.getTokenStatsTracker().startSpan(context)).thenReturn(Future.succeededFuture());
        when(proxy.getClient()).thenReturn(httpClient);
        when(proxy.getClientOptions()).thenReturn(new HttpClientOptions());
        when(context.getUserId()).thenReturn("test-user");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getInterceptors()).thenReturn(apiKeyData.getInterceptors());
        when(context.getConfig()).thenReturn(config);
        when(context.getRequest()).thenReturn(serverRequest);
        when(serverRequest.headers()).thenReturn(new HeadersMultiMap());
        when(serverRequest.body()).thenReturn(Future.succeededFuture(Buffer.buffer("")));
        when(serverRequest.method()).thenReturn(HttpMethod.GET);
        doAnswer(invocation -> {
            testContext.completeNow();
            return Future.failedFuture(new RuntimeException("abort"));
        }).when(httpClient).request(any(RequestOptions.class));
        doCallRealMethod().when(context).setDeployment(any());
        doCallRealMethod().when(context).getDeployment();

        controller("dial_test-deployment_123", GET).handle();

        await(testContext);

        verify(context).setProxyApiKeyData(argThat(data -> data.getInterceptorIndex() == 1));
        verify(httpClient).request(argThat(opts ->
                "interceptor2".equals(opts.getHost())
                && "/responses/dial_test-deployment_123".equals(opts.getURI().toString())));
        verify(proxy.getResponsesApiClient(), never()).send(any(), any(), any(), any());
    }

    @Test
    public void testDeploymentInterceptorForwardsToInterceptor(Vertx vertx, VertxTestContext testContext) throws Throwable {
        ResponseMapping mapping = ResponseMapping.builder()
                .upstreamResponseId("upstream-id-123")
                .upstreamKey("endpoint")
                .deploymentName("test-deployment")
                .initiatorBucket("Users/test-user/")
                .build();
        Model deployment = new Model();
        deployment.setName("test-deployment");
        deployment.setResponsesEndpoint("http://adapter/responses");

        Interceptor interceptor1 = new Interceptor();
        interceptor1.setResponsesEndpoint("http://interceptor1/responses");

        Config config = new Config();
        config.setInterceptors(Map.of("interceptor1", interceptor1));

        HttpClient httpClient = mock(HttpClient.class, RETURNS_DEEP_STUBS);

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getDeploymentService().getInterceptors(context, deployment)).thenReturn(List.of("interceptor1"));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        when(proxy.getTokenStatsTracker().startSpan(context)).thenReturn(Future.succeededFuture());
        when(proxy.getClient()).thenReturn(httpClient);
        when(proxy.getClientOptions()).thenReturn(new HttpClientOptions());
        when(context.getUserId()).thenReturn("test-user");
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.hasNextInterceptor()).thenReturn(true);
        when(context.getInterceptors()).thenReturn(List.of("interceptor1"));
        when(context.getConfig()).thenReturn(config);
        when(context.getRequest()).thenReturn(serverRequest);
        when(serverRequest.headers()).thenReturn(new HeadersMultiMap());
        when(serverRequest.body()).thenReturn(Future.succeededFuture(Buffer.buffer("")));
        when(serverRequest.method()).thenReturn(HttpMethod.GET);
        doAnswer(invocation -> {
            testContext.completeNow();
            return Future.failedFuture(new RuntimeException("abort"));
        }).when(httpClient).request(any(RequestOptions.class));
        doCallRealMethod().when(context).setDeployment(any());
        doCallRealMethod().when(context).getDeployment();

        controller("dial_test-deployment_123", GET).handle();

        await(testContext);

        verify(context).setProxyApiKeyData(argThat(data -> data.getInterceptorIndex() == 0));
        verify(httpClient).request(argThat(opts ->
                "interceptor1".equals(opts.getHost())
                && "/responses/dial_test-deployment_123".equals(opts.getURI().toString())));
        verify(proxy.getResponsesApiClient(), never()).send(any(), any(), any(), any());
    }

    private static Future<?> complete(VertxTestContext testContext) {
        testContext.completeNow();
        return Future.succeededFuture();
    }

    private static void await(VertxTestContext testContext) throws Throwable {
        testContext.awaitCompletion(1, TimeUnit.SECONDS);
        if (testContext.failed()) {
            throw testContext.causeOfFailure();
        }
    }

    private static AsyncTaskExecutor taskExecutor(Vertx vertx) {
        return new AsyncTaskExecutor(vertx, new JsonObject(Map.of("useVirtualThreads", false)));
    }
}

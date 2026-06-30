package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

    private ResponseItemController controller(String dialId, ResponseItemController.Operation op) {
        when(context.getDialResponseId()).thenReturn(dialId);
        return new ResponseItemController(proxy, context, op);
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
        Upstream upstream = new Upstream(null, "endpoint", "api-key", null, null, 0, 0, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);
        Buffer responseBody = Buffer.buffer("{\"id\":\"upstream-id-123\",\"status\":\"completed\"}");

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), eq("endpoint"))).thenReturn(upstreamRoute);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(proxy.getResponsesApiClient().send(anyString(), any(HttpMethod.class), any(Upstream.class)))
                .thenReturn(Future.succeededFuture(proxyResponse));
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(responseBody));
        when(proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("application/json");
        when(context.getResponse()).thenReturn(response);
        when(context.getRequest()).thenReturn(serverRequest);
        when(context.getUserId()).thenReturn("test-user");
        when(response.setStatusCode(200)).thenReturn(response);
        when(response.putHeader(any(CharSequence.class), anyString())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenAnswer(invocation -> complete(testContext));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));

        controller("dial_test-deployment_123", GET).handle();

        await(testContext);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpMethod> methodCaptor = ArgumentCaptor.forClass(HttpMethod.class);
        verify(proxy.getResponsesApiClient()).send(urlCaptor.capture(), methodCaptor.capture(), any(Upstream.class));
        assertEquals("http://adapter/responses/upstream-id-123", urlCaptor.getValue());
        assertEquals(HttpMethod.GET, methodCaptor.getValue());

        ArgumentCaptor<Buffer> bodyCaptor = ArgumentCaptor.forClass(Buffer.class);
        verify(response).end(bodyCaptor.capture());
        JsonNode sentJson = ProxyUtil.MAPPER.readTree(bodyCaptor.getValue().getBytes());
        assertEquals("dial_test-deployment_123", sentJson.path("id").asText());

        verify(proxy.getResponseMappingService(), never()).deleteMapping(anyString());
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
        Upstream upstream = new Upstream(null, "endpoint", "api-key", null, null, 0, 0, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClient httpClient = mock(HttpClient.class, RETURNS_DEEP_STUBS);
        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);
        Buffer responseBody = Buffer.buffer("{\"id\":\"upstream-id-123\",\"status\":\"completed\"}");

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), eq("endpoint"))).thenReturn(upstreamRoute);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(proxy.getClient()).thenReturn(httpClient);
        when(proxy.getClientOptions()).thenReturn(new HttpClientOptions());
        when(httpClient.request(any(RequestOptions.class))).thenReturn(Future.succeededFuture(proxyRequest));
        when(proxyRequest.send()).thenReturn(Future.succeededFuture(proxyResponse));
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(responseBody));
        when(proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("application/json");
        when(context.getResponse()).thenReturn(response);
        when(context.getRequest()).thenReturn(serverRequest);
        when(context.getUserId()).thenReturn("test-user");
        when(response.setStatusCode(200)).thenReturn(response);
        when(response.putHeader(any(CharSequence.class), anyString())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenAnswer(invocation -> complete(testContext));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));

        controller("dial_test-deployment_123", GET).handle();

        await(testContext);

        ArgumentCaptor<RequestOptions> optsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(httpClient).request(optsCaptor.capture());
        // new flow: base_url + /openai/v1/responses/{upstreamId}
        assertEquals("/openai/v1/responses/upstream-id-123", optsCaptor.getValue().getURI());
        assertEquals("adapter", optsCaptor.getValue().getHost());
        assertEquals(HttpMethod.GET, optsCaptor.getValue().getMethod());
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
        Upstream upstream = new Upstream(null, "endpoint", "api-key", null, null, 0, 0, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);
        Buffer responseBody = Buffer.buffer("{\"id\":\"upstream-id-123\",\"status\":\"cancelled\"}");

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), eq("endpoint"))).thenReturn(upstreamRoute);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(proxy.getResponsesApiClient().send(anyString(), any(HttpMethod.class), any(Upstream.class)))
                .thenReturn(Future.succeededFuture(proxyResponse));
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(responseBody));
        when(proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("application/json");
        when(context.getResponse()).thenReturn(response);
        when(context.getRequest()).thenReturn(serverRequest);
        when(context.getUserId()).thenReturn("test-user");
        when(response.setStatusCode(200)).thenReturn(response);
        when(response.putHeader(any(CharSequence.class), anyString())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenAnswer(invocation -> complete(testContext));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));

        controller("dial_test-deployment_123", CANCEL).handle();

        await(testContext);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpMethod> methodCaptor = ArgumentCaptor.forClass(HttpMethod.class);
        verify(proxy.getResponsesApiClient()).send(urlCaptor.capture(), methodCaptor.capture(), any(Upstream.class));
        assertEquals("http://adapter/responses/upstream-id-123/cancel", urlCaptor.getValue());
        assertEquals(HttpMethod.POST, methodCaptor.getValue());
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
        Upstream upstream = new Upstream(null, "endpoint", "api-key", null, null, 0, 0, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getBackgroundJobService().isJobActive(anyString())).thenReturn(Future.succeededFuture(false));
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), eq("endpoint"))).thenReturn(upstreamRoute);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(proxy.getResponsesApiClient().send(anyString(), any(HttpMethod.class), any(Upstream.class)))
                .thenReturn(Future.succeededFuture(proxyResponse));
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(Buffer.buffer("")));
        when(proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(null);
        when(context.getResponse()).thenReturn(response);
        when(context.getRequest()).thenReturn(serverRequest);
        when(context.getUserId()).thenReturn("test-user");
        when(response.setStatusCode(200)).thenReturn(response);
        when(response.putHeader(any(CharSequence.class), anyString())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenAnswer(invocation -> complete(testContext));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));

        controller("dial_test-deployment_del", DELETE).handle();

        await(testContext);

        verify(proxy.getResponseMappingService()).deleteMapping(anyString());
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
        Upstream upstream = new Upstream(null, "endpoint", "api-key", null, null, 0, 0, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getBackgroundJobService().isJobActive(anyString())).thenReturn(Future.succeededFuture(false));
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), eq("endpoint"))).thenReturn(upstreamRoute);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(proxy.getResponsesApiClient().send(anyString(), any(HttpMethod.class), any(Upstream.class)))
                .thenReturn(Future.succeededFuture(proxyResponse));
        when(proxyResponse.statusCode()).thenReturn(400);
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(Buffer.buffer("{\"id\":\"upstream-id-del\"}")));
        when(proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("application/json");
        when(context.getResponse()).thenReturn(response);
        when(context.getRequest()).thenReturn(serverRequest);
        when(context.getUserId()).thenReturn("test-user");
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

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), eq("missing-upstream-key")))
                .thenThrow(new HttpException(HttpStatus.BAD_REQUEST, "Unknown upstream id missing-upstream-key"));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        when(context.getUserId()).thenReturn("test-user");
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
        Upstream upstream = new Upstream(null, "endpoint", "api-key", null, null, 0, 0, null);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), eq("endpoint"))).thenReturn(upstreamRoute);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(proxy.getResponsesApiClient().send(anyString(), any(HttpMethod.class), any(Upstream.class)))
                .thenReturn(Future.succeededFuture(proxyResponse));
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(Buffer.buffer("")));
        when(proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(null);
        when(context.getResponse()).thenReturn(response);
        when(context.getRequest()).thenReturn(serverRequest);
        when(context.getUserId()).thenReturn("test-user");
        when(response.setStatusCode(200)).thenReturn(response);
        when(response.putHeader(any(CharSequence.class), anyString())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenAnswer(invocation -> complete(testContext));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));

        controller("dial_test-deployment_empty", GET).handle();

        await(testContext);

        ArgumentCaptor<Buffer> bodyCaptor = ArgumentCaptor.forClass(Buffer.class);
        verify(response).end(bodyCaptor.capture());
        assertEquals(0, bodyCaptor.getValue().length());
        verify(proxy.getResponseMappingService(), never()).deleteMapping(anyString());
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
        Upstream upstream = new Upstream(null, "endpoint", "api-key", null, null, 0, 0, null);
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

        when(proxy.getResponseMappingService().getMapping(anyString())).thenReturn(mapping);
        when(proxy.getDeploymentService().findDeployment(context, "test-deployment")).thenReturn(deployment);
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), eq("endpoint"))).thenReturn(upstreamRoute);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(proxy.getResponsesApiClient().send(anyString(), any(HttpMethod.class), any(Upstream.class)))
                .thenReturn(Future.succeededFuture(proxyResponse));
        when(context.getRequest()).thenReturn(serverRequest);
        when(serverRequest.query()).thenReturn("stream=true");
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
            testContext.completeNow();
            return Future.succeededFuture();
        }).when(response).end(any(Buffer.class));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));

        controller("dial_test-deployment_stream", GET).handle();

        await(testContext);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(proxy.getResponsesApiClient()).send(urlCaptor.capture(), any(HttpMethod.class), any(Upstream.class));
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

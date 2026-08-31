package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ConsentService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ResponseMappingService;
import com.epam.aidial.core.server.token.CompletionTokensDetails;
import com.epam.aidial.core.server.token.PromptTokensDetails;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.epam.aidial.core.server.Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON;
import static com.epam.aidial.core.storage.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, VertxExtension.class})
public class ResponsesControllerTest {
    private static final String PER_REQUEST_KEY = "per-request-key";

    @Mock
    private ProxyContext context;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Proxy proxy;

    @Mock
    private HttpServerRequest request;

    @Mock
    private HttpServerResponse response;

    @Mock
    private ConsentService consentService;

    @InjectMocks
    private ResponsesController controller;

    @BeforeEach
    void stubRequestPath() {
        // resolveRequestUri always consults the ingress path (even though the legacy flow ignores it)
        lenient().when(request.path()).thenReturn("/openai/v1/responses");
    }

    @Test
    public void testUnsupportedContentType() {
        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("unsupported");

        controller.handle();

        verify(context).respond(UNSUPPORTED_MEDIA_TYPE, "Only application/json is supported");
    }

    @Test
    void testUnprocessableEntity(VertxTestContext textContext) throws Throwable {
        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(Buffer.buffer("not-json")));
        when(context.getRequest()).thenReturn(request);
        when(context.respond(any(HttpStatus.class), anyString()))
                .thenAnswer(invocation -> complete(textContext));

        controller.handle();

        await(textContext);

        verify(context).respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
    }

    @Test
    public void testDeploymentNotFound(Vertx vertx, VertxTestContext textContext) throws Throwable {
        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(Buffer.buffer("{\"model\":\"unknown\"}")));
        when(context.getRequest()).thenReturn(request);
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.respond(any(HttpStatus.class), anyString()))
                .thenAnswer(invocation -> complete(textContext));
        when(proxy.getDeploymentService().findDeployment(context, "unknown"))
                .thenThrow(new ResourceNotFoundException("not found error"));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));

        controller.handle();

        await(textContext);

        verify(context).respond(HttpStatus.NOT_FOUND, "not found error");
    }

    @Test
    public void testDeploymentUserConsentError(Vertx vertx, VertxTestContext textContext) throws Throwable {
        Deployment deployment = new Model();

        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(Buffer.buffer("{\"model\":\"test\"}")));
        when(context.getRequest()).thenReturn(request);
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.respond(any(HttpStatus.class), anyString()))
                .thenAnswer(invocation -> complete(textContext));
        when(proxy.getDeploymentService().findDeployment(context, "test"))
                .thenReturn(deployment);
        doThrow(new PermissionDeniedException("permission error"))
                .when(consentService)
                .verifyUserConsent(context, deployment);
        when(proxy.getConsentService()).thenReturn(consentService);
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));

        controller.handle();

        await(textContext);

        verify(context).respond(HttpStatus.FORBIDDEN, "permission error");
    }

    @Test
    public void testDeploymentNoResponsesEndpoint(Vertx vertx, VertxTestContext textContext) throws Throwable {
        Deployment deployment = new Model();

        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(Buffer.buffer("{\"model\":\"test\"}")));
        when(context.getRequest()).thenReturn(request);
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.respond(any(HttpException.class)))
                .thenAnswer(invocation -> complete(textContext));
        when(proxy.getDeploymentService().findDeployment(context, "test"))
                .thenReturn(deployment);
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));

        controller.handle();

        await(textContext);

        verify(context).respond(argThat((HttpException e) -> e.getStatus() == HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    public void testDeploymentRateLimitError(Vertx vertx, VertxTestContext textContext) throws Throwable {
        Deployment deployment = new Model();
        deployment.setName("test");
        deployment.setResponsesEndpoint("http://adapter/responses");

        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(Buffer.buffer("{\"model\":\"test\"}")));
        when(context.getRequest()).thenReturn(request);
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.respond(any(HttpException.class)))
                .thenAnswer(invocation -> complete(textContext));
        when(proxy.getDeploymentService().findDeployment(context, "test"))
                .thenReturn(deployment);
        when(proxy.getRateLimiter().limit(context, deployment)).thenReturn(Future.succeededFuture(
                new RateLimitResult(HttpStatus.TOO_MANY_REQUESTS, "rate limit error", null, 0)));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        doCallRealMethod().when(context).setDeployment(any());
        doCallRealMethod().when(context).getDeployment();

        controller.handle();

        await(textContext);

        verify(context).respond(argThat((HttpException e) ->
                e.getStatus() == HttpStatus.TOO_MANY_REQUESTS
                        && "{\"error\":{\"message\":\"rate limit error\",\"code\":\"429\"}}".equals(e.getMessage())));
        verify(context).setTraceOperation("Send request to test deployment");
        verify(context).setDeployment(deployment);
    }

    @Test
    public void testModelResponse(Vertx vertx, VertxTestContext textContext) throws Throwable {
        Model deployment = new Model();
        deployment.setName("test");
        deployment.setResponsesEndpoint("http://adapter/responses");
        HttpClient httpClient = mock(HttpClient.class, RETURNS_DEEP_STUBS);
        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        ApiKeyStore apiKeyStore = mock(ApiKeyStore.class);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);
        Upstream upstream = new Upstream(null, "endpoint", null, null, null, 0, 0, "endpoint", null, null);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setSourceDeployment("test-deployment");
        apiKeyData.setPerRequestKey(PER_REQUEST_KEY);
        apiKeyData.setExecutionPath(List.of());
        Map<String, AutoSharedData> inputFiles = Map.of(
                "files/public/file.txt", new AutoSharedData(ResourceAccessType.READ_ONLY));
        Map<String, AutoSharedData> outputFiles = Map.of(
                "files/public/image.png", new AutoSharedData(Set.of(ResourceAccessType.READ)));
        ApiKeyData updatedApiKeyData = new ApiKeyData();
        updatedApiKeyData.setAttachedFiles(outputFiles);
        Buffer requestBody = Buffer.buffer(normalizeJson("""
                {
                    "model": "test",
                    "input": [
                        {
                            "content": [
                                {
                                    "type": "input_file",
                                    "file_url": "files/public/file.txt"
                                }
                            ]
                        }
                    ]
                }
                """));
        Buffer responseBody = Buffer.buffer(normalizeJson("""
                {
                    "output": [
                        {
                            "type": "code_interpreter_call",
                            "outputs": [
                                {
                                    "type": "image",
                                    "url": "files/public/image.png"
                                }
                            ]
                        }
                    ],
                    "usage": {
                        "input_tokens": 19,
                        "input_tokens_details": {
                          "cached_tokens": 0
                        },
                        "output_tokens": 9,
                        "output_tokens_details": {
                          "reasoning_tokens": 0
                        },
                        "total_tokens": 28
                    },
                    "id": "dial_test_fixed-uuid-1234"
                }
                """));
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(19);
        tokenUsage.setCompletionTokens(9);
        PromptTokensDetails promptTokensDetails = new PromptTokensDetails();
        promptTokensDetails.setCachedTokens(0);
        tokenUsage.setPromptTokensDetails(promptTokensDetails);
        CompletionTokensDetails completionTokensDetails = new CompletionTokensDetails();
        completionTokensDetails.setReasoningTokens(0);
        tokenUsage.setCompletionTokensDetails(completionTokensDetails);
        tokenUsage.setTotalTokens(28);

        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(requestBody));
        when(request.headers()).thenReturn(new HeadersMultiMap());
        when(request.query()).thenReturn("arg=value");
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.POST);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(upstreamRoute.get()).thenReturn(upstream);
        when(context.getUpstreamRoute()).thenReturn(upstreamRoute);
        when(context.getRequest()).thenReturn(request);
        when(context.getResponse()).thenReturn(response);
        when(context.getConfig()).thenReturn(new Config());
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.copyWith(any())).thenReturn(context);
        when(context.getUserId()).thenReturn("test-user");
        when(proxyRequest.headers()).thenReturn(new HeadersMultiMap());
        when(proxyRequest.send(requestBody)).thenReturn(Future.succeededFuture(proxyResponse));
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(responseBody));
        when(response.getStatusCode()).thenReturn(200);
        when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());
        when(proxy.getDeploymentService().findDeployment(context, "test"))
                .thenReturn(deployment);
        when(proxy.getRateLimiter().limit(context, deployment))
                .thenReturn(Future.succeededFuture(RateLimitResult.SUCCESS));
        when(proxy.getRateLimiter().increase(any(), any(), any(), any(), any()))
                .thenReturn(Future.succeededFuture());
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        when(proxy.getClient()).thenReturn(httpClient);
        when(proxy.getClientOptions()).thenReturn(new HttpClientOptions());
        when(httpClient.request(any())).thenReturn(Future.succeededFuture(proxyRequest));
        when(proxy.getApiKeyStore()).thenReturn(apiKeyStore);
        when(proxy.getGenerator().get()).thenReturn("fixed-uuid-1234");

        when(proxy.getTokenStatsTracker().startSpan(context))
                .thenReturn(Future.succeededFuture());
        when(proxy.getAccessService().hasReadAccess(
                ResourceDescriptorFactory.fromPublicUrl("files/public/image.png"), context))
                .thenReturn(true);
        when(proxy.getAccessService().hasReadAccess(
                ResourceDescriptorFactory.fromPublicUrl("files/public/file.txt"), context))
                .thenReturn(true);
        doAnswer(invocation -> {
            ApiKeyData proxyApiKeyData = invocation.getArgument(0);
            // side effect
            proxyApiKeyData.setPerRequestKey(PER_REQUEST_KEY);
            return null;
        }).when(apiKeyStore).assignPerRequestApiKey(any());
        doAnswer(invocation -> {
            textContext.completeNow();
            return Future.succeededFuture(Boolean.TRUE);
        }).when(apiKeyStore).invalidatePerRequestApiKey(any());
        doCallRealMethod().when(context).setDeployment(any());
        doCallRealMethod().when(context).getDeployment();
        doCallRealMethod().when(context).setRequestBody(any());
        doCallRealMethod().when(context).getRequestBody();
        doCallRealMethod().when(context).setResponseBody(any());
        doCallRealMethod().when(context).getResponseBody();
        doCallRealMethod().when(context).setTokenUsage(any());
        doCallRealMethod().when(context).getTokenUsage();
        doCallRealMethod().when(context).setProxyApiKeyData(any());
        doCallRealMethod().when(context).getProxyApiKeyData();
        doCallRealMethod().when(context).setProxyResponse(any());
        doCallRealMethod().when(context).getProxyResponse();

        controller.handle();

        await(textContext);

        verify(httpClient).request(argThat(req ->
                "/responses?arg=value".equals(req.getURI().toString())));
        assertEquals(responseBody, context.getResponseBody());
        assertEquals(tokenUsage, context.getTokenUsage());
        // Ensure the list of attached files is updated before it's saved
        verify(apiKeyStore).assignPerRequestApiKey(argThat(arg ->
                inputFiles.equals(arg.getAttachedFiles())));
        verify(apiKeyStore).updatePerRequestApiKey(
                eq(PER_REQUEST_KEY),
                argThat(arg ->
                        ProxyUtil.convertToString(updatedApiKeyData).equals(arg.apply("{}"))));
    }


    @Test
    public void testApplicationResponse(Vertx vertx, VertxTestContext textContext) throws Throwable {
        Application deployment = new Application();
        deployment.setName("test");
        deployment.setResponsesEndpoint("http://adapter/responses");
        HttpClient httpClient = mock(HttpClient.class, RETURNS_DEEP_STUBS);
        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        ApiKeyStore apiKeyStore = mock(ApiKeyStore.class);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);
        Upstream upstream = new Upstream(null, "endpoint", null, null, null, 0, 0, "endpoint", null, null);
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setPerRequestKey(PER_REQUEST_KEY);
        Buffer requestBody = Buffer.buffer("{\"model\":\"test\"}");
        Buffer responseBody = Buffer.buffer(normalizeJson("""
                {
                    "usage": {
                        "input_tokens": 19,
                        "input_tokens_details": {
                          "cached_tokens": 0
                        },
                        "output_tokens": 9,
                        "output_tokens_details": {
                          "reasoning_tokens": 0
                        },
                        "total_tokens": 28
                    },
                    "id": "dial_test_fixed-uuid-1234"
                }
                """));
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(20);
        tokenUsage.setCompletionTokens(10);
        PromptTokensDetails promptTokensDetails = new PromptTokensDetails();
        promptTokensDetails.setCachedTokens(1);
        tokenUsage.setPromptTokensDetails(promptTokensDetails);
        tokenUsage.setTotalTokens(31);

        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(requestBody));
        when(request.headers()).thenReturn(new HeadersMultiMap());
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.POST);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(upstreamRoute.get()).thenReturn(upstream);
        when(context.getRequest()).thenReturn(request);
        when(context.getResponse()).thenReturn(response);
        when(context.getConfig()).thenReturn(new Config());
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);
        when(proxyRequest.headers()).thenReturn(new HeadersMultiMap());
        when(proxyRequest.send(requestBody)).thenReturn(Future.succeededFuture(proxyResponse));
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(responseBody));
        when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());
        when(proxy.getDeploymentService().findDeployment(context, "test"))
                .thenReturn(deployment);
        when(proxy.getRateLimiter().limit(context, deployment))
                .thenReturn(Future.succeededFuture(RateLimitResult.SUCCESS));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), isNull())).thenReturn(upstreamRoute);
        when(proxy.getClient()).thenReturn(httpClient);
        when(proxy.getClientOptions()).thenReturn(new HttpClientOptions());
        when(httpClient.request(any())).thenReturn(Future.succeededFuture(proxyRequest));
        when(proxy.getApplicationSchemaService().modifyEndpointsForCustomApplication(deployment))
                .thenReturn(deployment);
        when(proxy.getApiKeyStore()).thenReturn(apiKeyStore);
        when(proxy.getGenerator().get()).thenReturn("fixed-uuid-1234");

        when(proxy.getTokenStatsTracker().startSpan(context))
                .thenReturn(Future.succeededFuture());
        // the response body carries its own "usage" object, so it's captured as this app's
        // self-reported usage (see issue #1753) rather than read from the trace aggregate
        when(proxy.getTokenStatsTracker().updateDeploymentStats(any(), any(), any(), any()))
                .thenReturn(Future.succeededFuture(new TokenStatsTracker.UsageStats(tokenUsage, List.of())));
        doAnswer(invocation -> {
            textContext.completeNow();
            return Future.succeededFuture(Boolean.TRUE);
        }).when(apiKeyStore).invalidatePerRequestApiKey(any());
        doCallRealMethod().when(context).setDeployment(any());
        doCallRealMethod().when(context).getDeployment();
        doCallRealMethod().when(context).setRequestBody(any());
        doCallRealMethod().when(context).getRequestBody();
        doCallRealMethod().when(context).setResponseBody(any());
        doCallRealMethod().when(context).getResponseBody();
        doCallRealMethod().when(context).setTokenUsage(any());
        doCallRealMethod().when(context).getTokenUsage();
        doCallRealMethod().when(context).setUsagePerModel(any());
        doCallRealMethod().when(context).getUsagePerModel();
        doCallRealMethod().when(context).setUpstreamRoute(any());
        doCallRealMethod().when(context).getUpstreamRoute();
        doCallRealMethod().when(context).setProxyResponse(any());
        doCallRealMethod().when(context).getProxyResponse();

        controller.handle();

        await(textContext);

        assertEquals(responseBody, context.getResponseBody());

        TokenUsage expectedOwnUsage = new TokenUsage();
        expectedOwnUsage.setPromptTokens(19);
        expectedOwnUsage.setCompletionTokens(9);
        expectedOwnUsage.setTotalTokens(28);
        PromptTokensDetails expectedPromptDetails = new PromptTokensDetails();
        expectedPromptDetails.setCachedTokens(0);
        expectedOwnUsage.setPromptTokensDetails(expectedPromptDetails);
        CompletionTokensDetails expectedCompletionDetails = new CompletionTokensDetails();
        expectedCompletionDetails.setReasoningTokens(0);
        expectedOwnUsage.setCompletionTokensDetails(expectedCompletionDetails);
        assertEquals(expectedOwnUsage, context.getTokenUsage());
    }

    @Test
    public void testPreviousResponseIdRejected(VertxTestContext textContext) throws Throwable {
        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(
                Buffer.buffer("{\"model\":\"test\",\"previous_response_id\":\"resp_123\"}")));
        when(context.respond(any(HttpStatus.class), anyString()))
                .thenAnswer(invocation -> complete(textContext));

        controller.handle();

        await(textContext);

        verify(context).respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
    }

    @Test
    public void testConversationRejected(VertxTestContext textContext) throws Throwable {
        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(
                Buffer.buffer("{\"model\":\"test\",\"conversation\":[]}")));
        when(context.respond(any(HttpStatus.class), anyString()))
                .thenAnswer(invocation -> complete(textContext));

        controller.handle();

        await(textContext);

        verify(context).respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
    }

    @Test
    public void testResponseIdMappingCreated(Vertx vertx, VertxTestContext textContext) throws Throwable {
        Application deployment = new Application();
        deployment.setName("test");
        deployment.setResponsesEndpoint("http://adapter/responses");
        HttpClient httpClient = mock(HttpClient.class, RETURNS_DEEP_STUBS);
        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        ApiKeyStore apiKeyStore = mock(ApiKeyStore.class);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);
        Upstream upstream = new Upstream(null, "endpoint", null, null, null, 0, 0, "endpoint", null, null);
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setPerRequestKey(PER_REQUEST_KEY);
        Buffer requestBody = Buffer.buffer("{\"model\":\"test\",\"background\":true}");
        Buffer responseBody = Buffer.buffer("{\"id\":\"upstream-resp-id\",\"status\":\"completed\"}");
        String expectedDialId = "dial_test_fixed-uuid-1234";
        ResponseMapping expectedMapping = ResponseMapping.builder()
                .upstreamResponseId("upstream-resp-id")
                .upstreamKey("endpoint")
                .deploymentName("test")
                .initiatorBucket("Users/test-user/")
                .build();

        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(requestBody));
        when(request.headers()).thenReturn(new HeadersMultiMap());
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.POST);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(upstreamRoute.get()).thenReturn(upstream);
        when(context.getRequest()).thenReturn(request);
        when(context.getResponse()).thenReturn(response);
        when(context.getConfig()).thenReturn(new Config());
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);
        when(proxyRequest.headers()).thenReturn(new HeadersMultiMap());
        when(proxyRequest.send(any(Buffer.class))).thenReturn(Future.succeededFuture(proxyResponse));
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(responseBody));
        when(proxyResponse.headers()).thenReturn(new HeadersMultiMap());
        when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());
        when(proxy.getDeploymentService().findDeployment(context, "test")).thenReturn(deployment);
        when(proxy.getRateLimiter().limit(context, deployment))
                .thenReturn(Future.succeededFuture(RateLimitResult.SUCCESS));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), isNull())).thenReturn(upstreamRoute);
        when(proxy.getClient()).thenReturn(httpClient);
        when(proxy.getClientOptions()).thenReturn(new HttpClientOptions());
        when(httpClient.request(any())).thenReturn(Future.succeededFuture(proxyRequest));
        when(proxy.getApplicationSchemaService().modifyEndpointsForCustomApplication(deployment))
                .thenReturn(deployment);
        when(proxy.getApiKeyStore()).thenReturn(apiKeyStore);
        when(proxy.getTokenStatsTracker().startSpan(context)).thenReturn(Future.succeededFuture());
        when(proxy.getTokenStatsTracker().getUsageStats(context))
                .thenReturn(Future.succeededFuture(new TokenStatsTracker.UsageStats(new TokenUsage(), List.of())));
        ResponseMappingService responseMappingService = proxy.getResponseMappingService();
        when(responseMappingService.saveMapping(any(), any())).thenReturn(expectedDialId);

        when(context.getUserId()).thenReturn("test-user");
        doAnswer(invocation -> {
            textContext.completeNow();
            return Future.succeededFuture(Boolean.TRUE);
        }).when(apiKeyStore).invalidatePerRequestApiKey(any());
        doCallRealMethod().when(context).setDeployment(any());
        doCallRealMethod().when(context).getDeployment();
        doCallRealMethod().when(context).setRequestBody(any());
        doCallRealMethod().when(context).getRequestBody();
        doCallRealMethod().when(context).setResponseBody(any());
        doCallRealMethod().when(context).getResponseBody();
        doCallRealMethod().when(context).setTokenUsage(any());
        doCallRealMethod().when(context).getTokenUsage();
        doCallRealMethod().when(context).setUpstreamRoute(any());
        doCallRealMethod().when(context).getUpstreamRoute();
        doCallRealMethod().when(context).setProxyResponse(any());
        doCallRealMethod().when(context).getProxyResponse();
        doCallRealMethod().when(context).setStoreResponse(anyBoolean());
        doCallRealMethod().when(context).isStoreResponse();

        controller.handle();

        await(textContext);

        verify(responseMappingService).saveMapping(eq(context), eq(expectedMapping));
        ArgumentCaptor<Buffer> bodyCaptor = ArgumentCaptor.forClass(Buffer.class);
        verify(response).end(bodyCaptor.capture());
        JsonNode sentJson = ProxyUtil.MAPPER.readTree(bodyCaptor.getValue().getBytes());
        assertEquals(expectedDialId, sentJson.path("id").asText());
    }

    @Test
    public void testStreamingResponse(Vertx vertx, VertxTestContext textContext) throws Throwable {
        Application deployment = new Application();
        deployment.setName("test");
        deployment.setResponsesEndpoint("http://adapter/responses");
        HttpClient httpClient = mock(HttpClient.class, RETURNS_DEEP_STUBS);
        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        ApiKeyStore apiKeyStore = mock(ApiKeyStore.class);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);
        Upstream upstream = new Upstream(null, "endpoint", null, null, null, 0, 0, "endpoint", null, null);
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setPerRequestKey(PER_REQUEST_KEY);
        String upstreamId = "upstream-resp-stream";
        String expectedDialId = "dial_test_fixed-uuid-1234";
        String sseContent = "event: response.created\n"
                + "data: {\"response\":{\"id\":\"" + upstreamId + "\"}}\n\n"
                + "event: response.completed\n"
                + "data: {\"response\":{\"id\":\"" + upstreamId + "\"}}\n\n";

        AtomicReference<Handler<Buffer>> chunkHandlerRef = new AtomicReference<>();
        AtomicReference<Handler<Void>> endHandlerRef = new AtomicReference<>();
        List<Buffer> writtenChunks = new ArrayList<>();

        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(Buffer.buffer("{\"model\":\"test\",\"stream\":true}")));
        when(request.headers()).thenReturn(new HeadersMultiMap());
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.POST);
        when(upstreamRoute.next()).thenReturn(upstream);
        when(upstreamRoute.get()).thenReturn(upstream);
        when(context.getRequest()).thenReturn(request);
        when(context.getResponse()).thenReturn(response);
        when(context.getConfig()).thenReturn(new Config());
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);
        when(proxyRequest.headers()).thenReturn(new HeadersMultiMap());
        when(proxyRequest.send(any(Buffer.class))).thenReturn(Future.succeededFuture(proxyResponse));
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
        when(response.setChunked(anyBoolean())).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        when(response.headers()).thenReturn(new HeadersMultiMap());
        doAnswer(inv -> {
            writtenChunks.add(inv.getArgument(0));
            return null;
        }).when(response).write(any(Buffer.class), any());
        when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());
        when(proxy.getDeploymentService().findDeployment(context, "test")).thenReturn(deployment);
        when(proxy.getRateLimiter().limit(context, deployment))
                .thenReturn(Future.succeededFuture(RateLimitResult.SUCCESS));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), isNull())).thenReturn(upstreamRoute);
        when(proxy.getClient()).thenReturn(httpClient);
        when(proxy.getClientOptions()).thenReturn(new HttpClientOptions());
        when(httpClient.request(any())).thenReturn(Future.succeededFuture(proxyRequest));
        when(proxy.getApplicationSchemaService().modifyEndpointsForCustomApplication(deployment))
                .thenReturn(deployment);
        when(proxy.getApiKeyStore()).thenReturn(apiKeyStore);
        when(context.getUserId()).thenReturn("test-user");
        when(proxy.getResponseMappingService().saveMapping(any(), any())).thenReturn(expectedDialId);
        when(proxy.getBackgroundJobService().deleteJob(anyString())).thenReturn(Future.succeededFuture(Boolean.TRUE));

        when(proxy.getTokenStatsTracker().startSpan(context)).thenReturn(Future.succeededFuture());
        when(proxy.getTokenStatsTracker().getUsageStats(context))
                .thenReturn(Future.succeededFuture(new TokenStatsTracker.UsageStats(new TokenUsage(), List.of())));
        doAnswer(invocation -> {
            textContext.completeNow();
            return Future.succeededFuture(Boolean.TRUE);
        }).when(apiKeyStore).invalidatePerRequestApiKey(any());
        doCallRealMethod().when(context).setDeployment(any());
        doCallRealMethod().when(context).getDeployment();
        doCallRealMethod().when(context).setRequestBody(any());
        doCallRealMethod().when(context).getRequestBody();
        doCallRealMethod().when(context).setResponseBody(any());
        doCallRealMethod().when(context).getResponseBody();
        doCallRealMethod().when(context).setTokenUsage(any());
        doCallRealMethod().when(context).getTokenUsage();
        doCallRealMethod().when(context).setUpstreamRoute(any());
        doCallRealMethod().when(context).getUpstreamRoute();
        doCallRealMethod().when(context).setProxyResponse(any());
        doCallRealMethod().when(context).setStreamingRequest(anyBoolean());
        doCallRealMethod().when(context).isStreamingRequest();
        doCallRealMethod().when(context).setStoreResponse(anyBoolean());
        doCallRealMethod().when(context).isStoreResponse();

        controller.handle();

        await(textContext);

        // Non-last SSE event (response.created) written with rewritten dial id
        assertEquals(1, writtenChunks.size());
        String createdEvent = writtenChunks.get(0).toString();
        assertTrue(createdEvent.contains(expectedDialId));
        assertFalse(createdEvent.contains(upstreamId));

        // Last SSE event (response.completed) sent via end() also has rewritten dial id
        ArgumentCaptor<Buffer> endCaptor = ArgumentCaptor.forClass(Buffer.class);
        verify(response).end(endCaptor.capture());
        String completedEvent = endCaptor.getValue().toString();
        assertTrue(completedEvent.contains(expectedDialId));
        assertFalse(completedEvent.contains(upstreamId));
    }

    @Test
    public void testUpstreamWithoutIdRejected(Vertx vertx, VertxTestContext textContext) throws Throwable {
        Application deployment = new Application();
        deployment.setName("test");
        deployment.setResponsesEndpoint("http://adapter/responses");
        ApiKeyStore apiKeyStore = mock(ApiKeyStore.class);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        Upstream upstream = new Upstream(null, "endpoint", null, null, null, 0, 0, null, null, null);
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setPerRequestKey(PER_REQUEST_KEY);

        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(Buffer.buffer("{\"model\":\"test\"}")));
        when(request.headers()).thenReturn(new HeadersMultiMap());
        when(upstreamRoute.next()).thenReturn(upstream);
        when(upstreamRoute.get()).thenReturn(upstream);
        when(context.getRequest()).thenReturn(request);
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);
        when(context.respond(any(HttpStatus.class), anyString()))
                .thenAnswer(invocation -> complete(textContext));
        when(proxy.getDeploymentService().findDeployment(context, "test")).thenReturn(deployment);
        when(proxy.getRateLimiter().limit(context, deployment))
                .thenReturn(Future.succeededFuture(RateLimitResult.SUCCESS));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), isNull())).thenReturn(upstreamRoute);
        when(proxy.getApplicationSchemaService().modifyEndpointsForCustomApplication(deployment))
                .thenReturn(deployment);
        when(proxy.getApiKeyStore()).thenReturn(apiKeyStore);
        when(proxy.getTokenStatsTracker().startSpan(context)).thenReturn(Future.succeededFuture());
        when(apiKeyStore.invalidatePerRequestApiKey(any())).thenReturn(Future.succeededFuture(Boolean.TRUE));
        doCallRealMethod().when(context).setDeployment(any());
        doCallRealMethod().when(context).getDeployment();
        doCallRealMethod().when(context).setRequestBody(any());
        doCallRealMethod().when(context).setUpstreamRoute(any());
        doCallRealMethod().when(context).getUpstreamRoute();

        controller.handle();

        await(textContext);

        verify(context).respond(HttpStatus.SERVICE_UNAVAILABLE, "Upstream is missing required id");
    }

    @Test
    public void testBackgroundJobRecordSaved(Vertx vertx, VertxTestContext textContext) throws Throwable {
        Application deployment = new Application();
        deployment.setName("test");
        deployment.setResponsesEndpoint("http://adapter/responses");
        HttpClient httpClient = mock(HttpClient.class, RETURNS_DEEP_STUBS);
        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        ApiKeyStore apiKeyStore = mock(ApiKeyStore.class);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);
        Upstream upstream = new Upstream(null, "endpoint", null, null, null, 0, 0, "endpoint", null, null);
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setPerRequestKey(PER_REQUEST_KEY);
        Buffer requestBody = Buffer.buffer("{\"model\":\"test\",\"background\":true}");
        Buffer responseBody = Buffer.buffer("{\"id\":\"upstream-resp-id\",\"status\":\"completed\"}");
        String expectedDialId = "dial_test_fixed-uuid-1234";

        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(requestBody));
        when(request.headers()).thenReturn(new HeadersMultiMap());
        when(upstreamRoute.next()).thenReturn(upstream);
        when(upstreamRoute.get()).thenReturn(upstream);
        when(context.getRequest()).thenReturn(request);
        when(context.getResponse()).thenReturn(response);
        when(context.getConfig()).thenReturn(new Config());
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);
        when(proxyRequest.headers()).thenReturn(new HeadersMultiMap());
        when(proxyRequest.send(any(Buffer.class))).thenReturn(Future.succeededFuture(proxyResponse));
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(responseBody));
        when(proxyResponse.headers()).thenReturn(new HeadersMultiMap());
        when(proxy.getDeploymentService().findDeployment(context, "test")).thenReturn(deployment);
        when(proxy.getRateLimiter().limit(context, deployment))
                .thenReturn(Future.succeededFuture(RateLimitResult.SUCCESS));
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), isNull())).thenReturn(upstreamRoute);
        when(proxy.getClient()).thenReturn(httpClient);
        when(proxy.getClientOptions()).thenReturn(new HttpClientOptions());
        when(httpClient.request(any())).thenReturn(Future.succeededFuture(proxyRequest));
        when(proxy.getApplicationSchemaService().modifyEndpointsForCustomApplication(deployment))
                .thenReturn(deployment);
        when(proxy.getApiKeyStore()).thenReturn(apiKeyStore);
        when(proxy.getTokenStatsTracker().startSpan(context)).thenReturn(Future.succeededFuture());
        when(proxy.getResponseMappingService().saveMapping(any(), any())).thenReturn(expectedDialId);
        when(context.getUserId()).thenReturn("test-user");
        when(proxy.getBackgroundJobService().saveJob(anyString(), any())).thenAnswer(invocation -> {
            textContext.completeNow();
            return Future.<Void>succeededFuture();
        });
        doCallRealMethod().when(context).setDeployment(any());
        doCallRealMethod().when(context).getDeployment();
        doCallRealMethod().when(context).setRequestBody(any());
        doCallRealMethod().when(context).getRequestBody();
        doCallRealMethod().when(context).setResponseBody(any());
        doCallRealMethod().when(context).setUpstreamRoute(any());
        doCallRealMethod().when(context).getUpstreamRoute();
        doCallRealMethod().when(context).setProxyResponse(any());
        doCallRealMethod().when(context).setStoreResponse(anyBoolean());
        doCallRealMethod().when(context).isStoreResponse();
        doCallRealMethod().when(context).setBackgroundJob(anyBoolean());
        doCallRealMethod().when(context).isBackgroundJob();

        controller.handle();

        await(textContext);
    }

    @Test
    public void testInterceptorReentryDispatchesToNextInterceptor(Vertx vertx, VertxTestContext testContext) throws Throwable {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey(PER_REQUEST_KEY);
        apiKeyData.setInterceptors(List.of("interceptor1", "interceptor2"));
        apiKeyData.setInterceptorIndex(0);
        apiKeyData.setInitialDeployment("test-model");
        apiKeyData.setExecutionPath(List.of());

        Interceptor interceptor2 = new Interceptor();
        interceptor2.setResponsesEndpoint("http://interceptor2/responses");

        Config config = new Config();
        config.setInterceptors(Map.of("interceptor1", new Interceptor(), "interceptor2", interceptor2));

        HttpClient httpClient = mock(HttpClient.class, RETURNS_DEEP_STUBS);

        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(Buffer.buffer("{\"model\":\"test-model\"}")));
        when(request.headers()).thenReturn(new HeadersMultiMap());
        when(request.method()).thenReturn(HttpMethod.POST);
        when(context.getRequest()).thenReturn(request);
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getInterceptors()).thenReturn(apiKeyData.getInterceptors());
        when(context.getConfig()).thenReturn(config);
        // Resolved up front: the controller is still running on a Vert.x thread when await() returns,
        // and resolving a deep stub on `proxy` from two threads at once corrupts its stubbing container.
        DeploymentService deploymentService = proxy.getDeploymentService();
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        when(proxy.getTokenStatsTracker().startSpan(context)).thenReturn(Future.succeededFuture());
        when(proxy.getClient()).thenReturn(httpClient);
        when(proxy.getClientOptions()).thenReturn(new HttpClientOptions());
        doAnswer(invocation -> {
            testContext.completeNow();
            return Future.failedFuture(new RuntimeException("abort"));
        }).when(httpClient).request(any(RequestOptions.class));
        doCallRealMethod().when(context).setDeployment(any());
        doCallRealMethod().when(context).getDeployment();
        doCallRealMethod().when(context).setProxyApiKeyData(any());
        doCallRealMethod().when(context).getProxyApiKeyData();

        controller.handle();

        await(testContext);

        verify(deploymentService, never()).findDeployment(any(), any());
        verify(context).setProxyApiKeyData(argThat(data -> data.getInterceptorIndex() == 1));
        verify(httpClient).request(argThat(opts ->
                "interceptor2".equals(opts.getHost())
                && "/responses".equals(opts.getURI().toString())));
    }

    @Test
    public void testLastInterceptorReentryUsesInitialDeployment(Vertx vertx, VertxTestContext textContext) throws Throwable {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey(PER_REQUEST_KEY);
        apiKeyData.setInterceptors(List.of("interceptor1"));
        apiKeyData.setInterceptorIndex(0);
        apiKeyData.setInitialDeployment("actual-model");
        apiKeyData.setExecutionPath(List.of());

        Model deployment = new Model();
        deployment.setName("actual-model");
        deployment.setResponsesEndpoint("http://actual-model/responses");

        HttpClient httpClient = mock(HttpClient.class, RETURNS_DEEP_STUBS);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        Upstream upstream = new Upstream(null, "http://actual-model/responses", null, null, null, 0, 0, "endpoint", null, null);

        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(Buffer.buffer("{\"model\":\"ignored\"}")));
        when(request.headers()).thenReturn(new HeadersMultiMap());
        when(request.method()).thenReturn(HttpMethod.POST);
        when(context.getRequest()).thenReturn(request);
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        DeploymentService deploymentService = proxy.getDeploymentService();
        when(deploymentService.findDeployment(context, "actual-model")).thenReturn(deployment);
        when(proxy.getRateLimiter().limit(context, deployment)).thenReturn(Future.succeededFuture(RateLimitResult.SUCCESS));
        when(proxy.getTokenStatsTracker().startSpan(context)).thenReturn(Future.succeededFuture());
        when(proxy.getUpstreamRouteProvider().get(eq(deployment), isNull(), any(), isNull())).thenReturn(upstreamRoute);
        when(proxy.getClient()).thenReturn(httpClient);
        when(proxy.getClientOptions()).thenReturn(new HttpClientOptions());
        when(upstreamRoute.next()).thenReturn(upstream);
        when(upstreamRoute.get()).thenReturn(upstream);
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        doAnswer(invocation -> {
            textContext.completeNow();
            return Future.failedFuture(new RuntimeException("abort"));
        }).when(httpClient).request(any(RequestOptions.class));
        doCallRealMethod().when(context).setDeployment(any());
        doCallRealMethod().when(context).getDeployment();
        doCallRealMethod().when(context).setProxyApiKeyData(any());
        doCallRealMethod().when(context).getProxyApiKeyData();
        doCallRealMethod().when(context).setUpstreamRoute(any());
        doCallRealMethod().when(context).getUpstreamRoute();

        controller.handle();

        await(textContext);

        verify(deploymentService).findDeployment(context, "actual-model");
        verify(deploymentService, never()).findDeployment(any(), eq("ignored"));
        verify(httpClient).request(argThat(opts ->
                "actual-model".equals(opts.getHost())
                && "/responses".equals(opts.getURI().toString())));
    }

    private static Future<?> complete(VertxTestContext textContext) {
        textContext.completeNow();
        return Future.succeededFuture();
    }

    private static void await(VertxTestContext textContext) throws Throwable {
        textContext.awaitCompletion(10, TimeUnit.SECONDS);
        if (textContext.failed()) {
            throw textContext.causeOfFailure();
        }
    }

    private static AsyncTaskExecutor taskExecutor(Vertx vertx) {
        return new AsyncTaskExecutor(vertx, new JsonObject(Map.of("useVirtualThreads", false)));
    }

    private static String normalizeJson(String json) throws IOException {
        return ProxyUtil.convertToString(ProxyUtil.MAPPER.readTree(json));
    }
}

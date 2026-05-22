package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ConsentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.token.PromptTokensDetails;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.epam.aidial.core.server.Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON;
import static com.epam.aidial.core.storage.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
        when(context.respond(any(HttpException.class)))
                .thenAnswer(invocation -> complete(textContext));
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
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
        when(context.respond(any(HttpException.class)))
                .thenAnswer(invocation -> complete(textContext));
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
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
        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        ApiKeyStore apiKeyStore = mock(ApiKeyStore.class);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);
        Upstream upstream = new Upstream(null, "endpoint", null, null, 0, 0, null);
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
        Buffer responseBody = Buffer.buffer("""
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
                    }
                }
                """);
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(19);
        tokenUsage.setCompletionTokens(9);
        PromptTokensDetails promptTokensDetails = new PromptTokensDetails();
        promptTokensDetails.setCachedTokens(0);
        tokenUsage.setPromptTokensDetails(promptTokensDetails);
        tokenUsage.setTotalTokens(28);

        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(requestBody));
        when(request.headers()).thenReturn(new HeadersMultiMap());
        when(request.query()).thenReturn("arg=value");
        when(upstreamRoute.next()).thenReturn(upstream);
        when(upstreamRoute.get()).thenReturn(upstream);
        when(context.getUpstreamRoute()).thenReturn(upstreamRoute);
        when(context.getRequest()).thenReturn(request);
        when(context.getResponse()).thenReturn(response);
        when(context.getConfig()).thenReturn(new Config());
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.copyWith(any())).thenReturn(context);
        when(proxyRequest.headers()).thenReturn(new HeadersMultiMap());
        when(proxyRequest.send(requestBody)).thenReturn(Future.succeededFuture(proxyResponse));
        when(proxyResponse.statusCode()).thenReturn(200);
        when(response.getStatusCode()).thenReturn(200);
        when(response.end())
                .thenAnswer(invocation -> complete(textContext));
        when(proxy.getDeploymentService().findDeployment(context, "test"))
                .thenReturn(deployment);
        when(proxy.getRateLimiter().limit(context, deployment))
                .thenReturn(Future.succeededFuture(RateLimitResult.SUCCESS));
        when(proxy.getRateLimiter().increase(context, deployment))
                .thenReturn(Future.succeededFuture());
        when(proxy.getTokenStatsTracker().updateModelStats(context))
                .thenReturn(Future.succeededFuture());
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        when(proxy.getClient().request(any()))
                .thenReturn(Future.succeededFuture(proxyRequest));
        when(proxy.getApiKeyStore()).thenReturn(apiKeyStore);
        when(proxy.getTokenStatsTracker().startSpan(context))
                .thenReturn(Future.succeededFuture());
        when(proxy.getAccessService().hasReadAccess(
                ResourceDescriptorFactory.fromPublicUrl("files/public/image.png"), context))
                .thenReturn(true);
        when(proxy.getAccessService().hasReadAccess(
                ResourceDescriptorFactory.fromPublicUrl("files/public/file.txt"), context))
                .thenReturn(true);
        when(proxyResponse.handler(any())).thenAnswer(inv -> {
            Handler<Buffer> handler = inv.getArgument(0);
            handler.handle(responseBody);
            return proxyResponse;
        });
        when(proxyResponse.endHandler(any())).thenAnswer(inv -> {
            Handler<Buffer> handler = inv.getArgument(0);
            handler.handle(null);
            return proxyResponse;
        });
        doAnswer(invocation -> {
            ApiKeyData proxyApiKeyData = invocation.getArgument(0);
            // side effect
            proxyApiKeyData.setPerRequestKey(PER_REQUEST_KEY);
            return null;
        }).when(apiKeyStore).assignPerRequestApiKey(any());
        doCallRealMethod().when(context).setDeployment(any());
        doCallRealMethod().when(context).getDeployment();
        doCallRealMethod().when(context).setRequestBody(any());
        doCallRealMethod().when(context).getRequestBody();
        doCallRealMethod().when(context).setResponseBody(any());
        doCallRealMethod().when(context).getResponseBody();
        doCallRealMethod().when(context).setResponseStream(any());
        doCallRealMethod().when(context).getResponseStream();
        doCallRealMethod().when(context).setTokenUsage(any());
        doCallRealMethod().when(context).getTokenUsage();
        doCallRealMethod().when(context).setProxyResponse(any());
        doCallRealMethod().when(context).getProxyResponse();
        doCallRealMethod().when(context).setProxyApiKeyData(any());
        doCallRealMethod().when(context).getProxyApiKeyData();

        controller.handle();

        await(textContext);

        verify(proxy.getClient()).request(argThat(req ->
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
        HttpClientRequest proxyRequest = mock(HttpClientRequest.class, RETURNS_DEEP_STUBS);
        ApiKeyStore apiKeyStore = mock(ApiKeyStore.class);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);
        Upstream upstream = new Upstream(null, "endpoint", null, null, 0, 0, null);
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setPerRequestKey(PER_REQUEST_KEY);
        Buffer requestBody = Buffer.buffer("{\"model\":\"test\"}");
        Buffer responseBody = Buffer.buffer("""
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
                    }
                }
                """);
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
        when(response.end())
                .thenAnswer(invocation -> complete(textContext));
        when(proxy.getDeploymentService().findDeployment(context, "test"))
                .thenReturn(deployment);
        when(proxy.getRateLimiter().limit(context, deployment))
                .thenReturn(Future.succeededFuture(RateLimitResult.SUCCESS));
        when(proxy.getRateLimiter().increase(context, deployment))
                .thenReturn(Future.succeededFuture());
        when(proxy.getTokenStatsTracker().updateModelStats(context))
                .thenReturn(Future.succeededFuture());
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor(vertx));
        when(proxy.getUpstreamRouteProvider().get(deployment, null, (String) null)).thenReturn(upstreamRoute);
        when(proxy.getClient().request(any()))
                .thenReturn(Future.succeededFuture(proxyRequest));
        when(proxy.getApplicationSchemaService().modifyEndpointsForCustomApplication(deployment))
                .thenReturn(deployment);
        when(proxy.getApiKeyStore()).thenReturn(apiKeyStore);
        when(proxy.getTokenStatsTracker().startSpan(context))
                .thenReturn(Future.succeededFuture());
        when(proxy.getTokenStatsTracker().getTokenStats(context))
                .thenReturn(Future.succeededFuture(tokenUsage));
        when(proxyResponse.handler(any())).thenAnswer(inv -> {
            Handler<Buffer> handler = inv.getArgument(0);
            handler.handle(responseBody);
            return proxyResponse;
        });
        when(proxyResponse.endHandler(any())).thenAnswer(inv -> {
            Handler<Buffer> handler = inv.getArgument(0);
            handler.handle(null);
            return proxyResponse;
        });
        doCallRealMethod().when(context).setDeployment(any());
        doCallRealMethod().when(context).getDeployment();
        doCallRealMethod().when(context).setRequestBody(any());
        doCallRealMethod().when(context).getRequestBody();
        doCallRealMethod().when(context).setResponseBody(any());
        doCallRealMethod().when(context).getResponseBody();
        doCallRealMethod().when(context).setResponseStream(any());
        doCallRealMethod().when(context).getResponseStream();
        doCallRealMethod().when(context).setTokenUsage(any());
        doCallRealMethod().when(context).getTokenUsage();
        doCallRealMethod().when(context).setUpstreamRoute(any());
        doCallRealMethod().when(context).getUpstreamRoute();

        controller.handle();

        await(textContext);

        assertEquals(responseBody, context.getResponseBody());
        assertEquals(tokenUsage, context.getTokenUsage());
    }

    private static Future<?> complete(VertxTestContext textContext) {
        textContext.completeNow();
        return Future.succeededFuture();
    }

    private static void await(VertxTestContext textContext) throws Throwable {
        textContext.awaitCompletion(1, TimeUnit.SECONDS);
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

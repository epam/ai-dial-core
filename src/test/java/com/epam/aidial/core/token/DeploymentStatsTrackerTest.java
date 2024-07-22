package com.epam.aidial.core.token;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.PricingUnit;
import com.epam.aidial.core.service.LockService;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.upstream.UpstreamRoute;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.Redisson;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.config.ConfigSupport;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("checkstyle:LineLength")
@ExtendWith(MockitoExtension.class)
public class DeploymentStatsTrackerTest {

    private static RedisServer redisServer;

    private static RedissonClient redissonClient;

    @Mock
    private Vertx vertx;

    @Mock
    private BlobStorage blobStorage;

    @InjectMocks
    private DeploymentCostStatsTracker tracker;

    @BeforeAll
    public static void beforeAll() throws IOException {
        redisServer = RedisServer.newRedisServer()
                .port(16370)
                .setting("bind 127.0.0.1")
                .setting("maxmemory 16M")
                .setting("maxmemory-policy volatile-lfu")
                .build();
        redisServer.start();
        ConfigSupport configSupport = new ConfigSupport();
        org.redisson.config.Config redisClientConfig = configSupport.fromJSON("""
                {
                  "singleServerConfig": {
                     "address": "redis://localhost:16370"
                  }
                }
                """, org.redisson.config.Config.class);

        redissonClient = Redisson.create(redisClientConfig);
    }

    @AfterAll
    public static void afterAll() throws IOException {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @BeforeEach
    public void beforeEach() {
        RKeys keys = redissonClient.getKeys();
        for (String key : keys.getKeys()) {
            keys.delete(key);
        }
        LockService lockService = new LockService(redissonClient, null);
        String resourceConfig = """
                  {
                    "maxSize" : 1048576,
                    "syncPeriod": 60000,
                    "syncDelay": 120000,
                    "syncBatch": 4096,
                    "cacheExpiration": 300000,
                    "compressionMinSize": 256
                  }
                """;
        ResourceService resourceService = new ResourceService(vertx, redissonClient, blobStorage, lockService, new JsonObject(resourceConfig), null);
        tracker = new DeploymentCostStatsTracker(resourceService, vertx);
    }

    /**
     * Tests the flow: chat back-end -> core -> app -> core -> model
     */
    @Test
    public void testWorkflow() {
        final String traceId = "trace-id";
        ProxyContext chatBackend = mock(ProxyContext.class);
        when(chatBackend.getSpanId()).thenReturn("chat");
        when(chatBackend.getTraceId()).thenReturn(traceId);
        when(chatBackend.getApiKeyData()).thenReturn(new ApiKeyData());

        // chat calls app -> core starts span
        tracker.startSpan(chatBackend);


        ProxyContext app = mock(ProxyContext.class);
        when(app.getSpanId()).thenReturn("app");
        when(app.getTraceId()).thenReturn(traceId);
        when(app.getParentSpanId()).thenReturn("chat");
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("key");
        when(app.getApiKeyData()).thenReturn(apiKeyData);
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setUnit(PricingUnit.TOKEN);
        pricing.setCompletion("1.0");
        pricing.setPrompt("1.0");
        model.setPricing(pricing);
        when(app.getDeployment()).thenReturn(model);
        when(app.getUpstreamRoute()).thenReturn(mock(UpstreamRoute.class, RETURNS_DEEP_STUBS));
        when(app.getResponse()).thenReturn(mock(HttpServerResponse.class, RETURNS_DEEP_STUBS));
        when(app.getResponseBody()).thenReturn(mock(Buffer.class));

        // app calls model -> core starts span
        tracker.startSpan(app);

        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        DeploymentCostStats deploymentCostStats = new DeploymentCostStats();
        TokenUsage modelTokenUsage = new TokenUsage();
        modelTokenUsage.setTotalTokens(100);
        modelTokenUsage.setCompletionTokens(80);
        modelTokenUsage.setPromptTokens(20);
        deploymentCostStats.setTokenUsage(modelTokenUsage);

        // core receives response from model
        when(app.getTokenUsage()).thenReturn(modelTokenUsage);
        when(app.getDeploymentCostStats()).thenReturn(deploymentCostStats);

        tracker.handleChunkResponse(Buffer.buffer(""), app);

        // core ends span for request to model
        tracker.endSpan(app);

        // core receives response from app
        Future<DeploymentCostStats> modelStatsFuture = tracker.getDeploymentStats(app);
        assertNotNull(modelStatsFuture);
        DeploymentCostStats modelStats = modelStatsFuture.result();
        assertEquals(100, modelStats.getTokenUsage().getTotalTokens());
        assertEquals(80, modelStats.getTokenUsage().getCompletionTokens());
        assertEquals(20, modelStats.getTokenUsage().getPromptTokens());
        assertEquals(new BigDecimal("100.0"), modelStats.getAggCost());
        assertEquals(new BigDecimal("100.0"), modelStats.getCost());

        // core receives response from app
        Future<DeploymentCostStats> appStatsFuture = tracker.getDeploymentStats(chatBackend);
        assertNotNull(appStatsFuture);
        DeploymentCostStats appStats = appStatsFuture.result();
        assertEquals(100, appStats.getTokenUsage().getTotalTokens());
        assertEquals(80, appStats.getTokenUsage().getCompletionTokens());
        assertEquals(20, appStats.getTokenUsage().getPromptTokens());
        assertEquals(new BigDecimal("100.0"), appStats.getAggCost());
        assertNull(appStats.getCost());

        // core ends span for request to app
        tracker.endSpan(chatBackend);
        assertNull(tracker.getDeploymentStats(chatBackend).result());
    }

    @Test
    public void testHandleRequestBody() throws IOException {
        ProxyContext context = mock(ProxyContext.class);
        Model model = new Model();
        model.setType(ModelType.CHAT);
        Pricing pricing = new Pricing();
        pricing.setUnit(PricingUnit.CHAR_WITHOUT_WHITESPACE);
        pricing.setCompletion("1.0");
        pricing.setPrompt("1.0");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);
        String request = """
                {
                  "messages": [
                    {
                      "role": "system",
                      "content": ""
                    },
                    {
                      "role": "user",
                      "content": "How are you?"
                    }
                  ],
                  "max_tokens": 500,
                  "temperature": 1,
                  "stream": false
                }
                """;
        Buffer chunk = Buffer.buffer(request);
        doCallRealMethod().when(context).setDeploymentCostStats(any(DeploymentCostStats.class));
        when(context.getDeploymentCostStats()).thenCallRealMethod();

        ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(new ByteBufInputStream(chunk.getByteBuf()));

        tracker.handleRequestBody(tree, context);

        assertEquals(10, context.getDeploymentCostStats().getRequestContentLength());
    }

    @Test
    public void testHandleChunkResponse_CharWithoutWhitespaces_StreamingCompleted() {
        ProxyContext context = mock(ProxyContext.class);
        Model model = new Model();
        model.setType(ModelType.CHAT);
        Pricing pricing = new Pricing();
        pricing.setUnit(PricingUnit.CHAR_WITHOUT_WHITESPACE);
        pricing.setCompletion("1.0");
        pricing.setPrompt("1.0");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);
        String response = """
                \n data:   [DONE] \n
                """;
        Buffer chunk = Buffer.buffer(response);
        DeploymentCostStats deploymentCostStats = new DeploymentCostStats();
        deploymentCostStats.setRequestContentLength(10);
        deploymentCostStats.setResponseContentLength(10);
        when(context.getDeploymentCostStats()).thenReturn(deploymentCostStats);

        tracker.handleChunkResponse(chunk, context);

        assertEquals(new BigDecimal("20.0"), deploymentCostStats.getCost());
        assertEquals(new BigDecimal("20.0"), deploymentCostStats.getAggCost());
    }

    @Test
    public void testHandleChunkResponse_TokenUsage_StreamingCompleted() {
        ProxyContext context = mock(ProxyContext.class);
        Model model = new Model();
        model.setType(ModelType.CHAT);
        Pricing pricing = new Pricing();
        pricing.setUnit(PricingUnit.TOKEN);
        pricing.setCompletion("1.0");
        pricing.setPrompt("1.0");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);
        String response = """
                \n data:   [DONE] \n
                """;
        Buffer chunk = Buffer.buffer(response);
        DeploymentCostStats deploymentCostStats = new DeploymentCostStats();
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(10);
        tokenUsage.setCompletionTokens(10);
        tokenUsage.setTotalTokens(20);
        deploymentCostStats.setTokenUsage(tokenUsage);
        when(context.getDeploymentCostStats()).thenReturn(deploymentCostStats);
        when(context.getTokenUsage()).thenAnswer(invocation -> deploymentCostStats.getTokenUsage());

        tracker.handleChunkResponse(chunk, context);

        assertEquals(new BigDecimal("20.0"), deploymentCostStats.getCost());
        assertEquals(new BigDecimal("20.0"), deploymentCostStats.getAggCost());
    }

    @Test
    public void testHandleChunkResponse_CharWithoutWhitespace_Streaming() {
        ProxyContext context = mock(ProxyContext.class);
        Model model = new Model();
        model.setType(ModelType.CHAT);
        Pricing pricing = new Pricing();
        pricing.setUnit(PricingUnit.CHAR_WITHOUT_WHITESPACE);
        pricing.setCompletion("1.0");
        pricing.setPrompt("1.0");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);
        String response = """
                \n data:   {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"this"}}],"usage":null} \n
                """;
        Buffer chunk = Buffer.buffer(response);
        DeploymentCostStats deploymentCostStats = new DeploymentCostStats();
        when(context.getDeploymentCostStats()).thenReturn(deploymentCostStats);

        tracker.handleChunkResponse(chunk, context);

        assertEquals(4, deploymentCostStats.getResponseContentLength());
    }

    @Test
    public void testHandleChunkResponse_CharWithoutWhitespace_SingleResponse() {
        ProxyContext context = mock(ProxyContext.class);
        Model model = new Model();
        model.setType(ModelType.CHAT);
        Pricing pricing = new Pricing();
        pricing.setUnit(PricingUnit.CHAR_WITHOUT_WHITESPACE);
        pricing.setCompletion("1.0");
        pricing.setPrompt("1.0");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);
        String response = """
                {
                   "choices": [
                     {
                       "index": 0,
                       "finish_reason": "stop",
                       "message": {
                         "role": "assistant",
                         "content": "A file is a named collection."
                       }
                     }
                   ],
                   "usage": {
                     "prompt_tokens": 4,
                     "completion_tokens": 343,
                     "total_tokens": 347
                   },
                   "id": "fd3be95a-c208-4dca-90cf-67e5082a4e5b",
                   "created": 1705319789,
                   "object": "chat.completion"
                 }
                """;
        Buffer chunk = Buffer.buffer(response);
        DeploymentCostStats deploymentCostStats = new DeploymentCostStats();
        when(context.getDeploymentCostStats()).thenReturn(deploymentCostStats);

        tracker.handleChunkResponse(chunk, context);

        assertEquals(24, deploymentCostStats.getResponseContentLength());
        assertEquals(new BigDecimal("24.0"), deploymentCostStats.getCost());
        assertEquals(new BigDecimal("24.0"), deploymentCostStats.getAggCost());
    }

    @Test
    public void testHandleChunkResponse_TokenUsage_Streaming() {
        ProxyContext context = mock(ProxyContext.class);
        Model model = new Model();
        model.setType(ModelType.CHAT);
        Pricing pricing = new Pricing();
        pricing.setUnit(PricingUnit.TOKEN);
        pricing.setCompletion("1.0");
        pricing.setPrompt("1.0");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);
        String response = """
                \n data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":"stop","delta":{}}],
                         "usage" \n\t\r : \n\t\r {
                             "junk_string": "junk",
                             "junk_integer" : 1,
                             "junk_float" : 1.0,
                             "junk_null" : null,
                             "junk_true" : true,
                             "junk_false" : false,
                             "completion_tokens": 10,
                             "prompt_tokens": 20,
                             "total_tokens": 30
                           }
                       }  \n
                """;
        Buffer chunk = Buffer.buffer(response);
        DeploymentCostStats deploymentCostStats = new DeploymentCostStats();
        when(context.getDeploymentCostStats()).thenReturn(deploymentCostStats);

        tracker.handleChunkResponse(chunk, context);

        assertEquals(10, deploymentCostStats.getTokenUsage().getCompletionTokens());
        assertEquals(20, deploymentCostStats.getTokenUsage().getPromptTokens());
        assertEquals(30, deploymentCostStats.getTokenUsage().getTotalTokens());
    }

    @Test
    public void testHandleChunkResponse_TokenUsage_SingleResponse() {
        ProxyContext context = mock(ProxyContext.class);
        Model model = new Model();
        model.setType(ModelType.CHAT);
        Pricing pricing = new Pricing();
        pricing.setUnit(PricingUnit.TOKEN);
        pricing.setCompletion("1.0");
        pricing.setPrompt("1.0");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);
        String response = """
                {
                   "choices": [
                     {
                       "index": 0,
                       "finish_reason": "stop",
                       "message": {
                         "role": "assistant",
                         "content": "A file is a named collection."
                       }
                     }
                   ],
                   "usage": {
                     "prompt_tokens": 4,
                     "completion_tokens": 343,
                     "total_tokens": 347
                   },
                   "id": "fd3be95a-c208-4dca-90cf-67e5082a4e5b",
                   "created": 1705319789,
                   "object": "chat.completion"
                 }
                """;
        Buffer chunk = Buffer.buffer(response);
        DeploymentCostStats deploymentCostStats = new DeploymentCostStats();
        when(context.getDeploymentCostStats()).thenReturn(deploymentCostStats);
        when(context.getTokenUsage()).thenAnswer(invocation -> deploymentCostStats.getTokenUsage());

        tracker.handleChunkResponse(chunk, context);

        assertEquals(343, deploymentCostStats.getTokenUsage().getCompletionTokens());
        assertEquals(4, deploymentCostStats.getTokenUsage().getPromptTokens());
        assertEquals(347, deploymentCostStats.getTokenUsage().getTotalTokens());
        assertEquals(new BigDecimal("347.0"), deploymentCostStats.getCost());
        assertEquals(new BigDecimal("347.0"), deploymentCostStats.getAggCost());
    }
}

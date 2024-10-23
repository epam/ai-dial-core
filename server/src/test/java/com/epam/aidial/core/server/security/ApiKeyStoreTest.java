package com.epam.aidial.core.server.security;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.data.ResourceAccessType;
import com.epam.aidial.core.server.service.LockService;
import com.epam.aidial.core.server.service.ResourceService;
import com.epam.aidial.core.server.storage.BlobStorage;
import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.apache.commons.lang3.mutable.MutableObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.Redisson;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.config.ConfigSupport;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ApiKeyStoreTest {

    private static RedisServer redisServer;

    private static RedissonClient redissonClient;

    @Mock
    private Vertx vertx;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private BlobStorage blobStorage;

    private ApiKeyStore store;

    @BeforeAll
    public static void beforeAll() throws IOException {
        redisServer = RedisServer.newRedisServer()
                .port(16370)
                .bind("127.0.0.1")
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
        store = new ApiKeyStore(resourceService, vertx);
    }

    @Test
    public void testAssignApiKey() {
        ApiKeyData apiKeyData = new ApiKeyData();
        store.assignPerRequestApiKey(apiKeyData);
        assertNotNull(apiKeyData.getPerRequestKey());
    }

    @Test
    public void testAddProjectKeys() {
        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Key key1 = new Key();
        key1.setProject("prj1");
        key1.setRole("role1");
        Map<String, Key> projectKeys1 = Map.of("key1", key1);

        store.addProjectKeys(projectKeys1);

        ApiKeyData apiKeyData = new ApiKeyData();
        store.assignPerRequestApiKey(apiKeyData);

        Key key2 = new Key();
        key2.setProject("prj1");
        key2.setRole("role1");
        Map<String, Key> projectKeys2 = Map.of("key2", key2);

        store.addProjectKeys(projectKeys2);

        // old key must be removed
        assertNull(store.getApiKeyData("key1").result());
        // new key must be accessed
        Future<ApiKeyData> res1 = store.getApiKeyData("key2");
        assertNotNull(res1.result());
        assertEquals(key2, res1.result().getOriginalKey());
        // existing per request key must be accessed
        assertNotNull(store.getApiKeyData(apiKeyData.getPerRequestKey()).result());

    }

    @Test
    public void testGetApiKeyData() {
        ApiKeyData apiKeyData = new ApiKeyData();
        store.assignPerRequestApiKey(apiKeyData);

        assertNotNull(apiKeyData.getPerRequestKey());

        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<ApiKeyData> res1  = store.getApiKeyData(apiKeyData.getPerRequestKey());
        assertNotNull(res1);
        assertEquals(apiKeyData, res1.result());

        assertTrue(store.getApiKeyData("unknown-key").failed());
    }

    @Test
    public void testInvalidateApiKey() {
        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });
        ApiKeyData apiKeyData = new ApiKeyData();
        store.assignPerRequestApiKey(apiKeyData);

        assertNotNull(apiKeyData.getPerRequestKey());

        store.invalidatePerRequestApiKey(apiKeyData);

        assertTrue(store.getApiKeyData(apiKeyData.getPerRequestKey()).failed());
    }

    @Test
    public void testUpdateApiKey() {
        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });
        ApiKeyData apiKeyData = new ApiKeyData();
        store.assignPerRequestApiKey(apiKeyData);

        assertNotNull(apiKeyData.getPerRequestKey());
        MutableObject<ApiKeyData> ref = new MutableObject<>();

        store.updatePerRequestApiKey(apiKeyData.getPerRequestKey(), json -> {
            ApiKeyData current = ProxyUtil.convertToObject(json, ApiKeyData.class);
            current.getAttachedFiles().put("a/b/c/file.txt", new AutoSharedData(ResourceAccessType.READ_ONLY));
            ref.setValue(current);
            return ProxyUtil.convertToString(current);
        });

        Future<ApiKeyData> res1  = store.getApiKeyData(apiKeyData.getPerRequestKey());
        assertNotNull(res1);
        assertEquals(ref.getValue(), res1.result());
    }
}

package com.epam.aidial.core.server.security;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ApiKeyStoreTest {

    private static RedisServer redisServer;

    private static RedissonClient redissonClient;

    @Mock
    private AsyncTaskExecutor taskExecutor;

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
        store = new ApiKeyStore(taskExecutor, redissonClient, null, new JsonObject());
    }

    @Test
    public void testAssignApiKey() {
        ApiKeyData apiKeyData = new ApiKeyData();
        store.assignPerRequestApiKey(apiKeyData);
        assertNotNull(apiKeyData.getPerRequestKey());
    }

    @Test
    public void testAddProjectKeys() {
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Key key1 = new Key();
        key1.setProject("prj1");
        key1.setRole("role1");
        Map<String, Key> projectKeys1 = Map.of("key1", key1);

        store.addProjectKeys(projectKeys1, Map.of());

        ApiKeyData apiKeyData = new ApiKeyData();
        store.assignPerRequestApiKey(apiKeyData);

        Key key2 = new Key();
        key2.setProject("prj1");
        key2.setRole("role1");
        Map<String, Key> projectKeys2 = Map.of("key2", key2);

        store.addProjectKeys(projectKeys2, Map.of());

        // old key must be removed
        assertNull(store.getApiKeyData("key1", null).result());
        // new key must be accessed
        Future<ApiKeyData> res1 = store.getApiKeyData("key2", null);
        assertNotNull(res1.result());
        assertEquals(key2, res1.result().getOriginalKey());
        // existing per request key must be accessed
        assertNotNull(store.getApiKeyData(apiKeyData.getPerRequestKey(), null).result());

    }

    @Test
    public void testAddProjectKeysFileSourced() {
        Key key = new Key();
        key.setProject("prj1");
        key.setRole("role1");
        Map<String, Key> projectKeys = Map.of("secret-value", key);

        store.addProjectKeys(projectKeys, Map.of());

        assertEquals("secret-value", key.getKey());
    }

    @Test
    public void testAddFileProjectKeyWithSlashInSecret() {
        Key key = new Key();
        key.setProject("prj1");
        key.setRole("role1");
        // File-mode secrets may be Base64 and contain '/' — the map key IS the secret and must be
        // back-filled verbatim (no map-key shape inference). See OQ-12.
        Map<String, Key> projectKeys = Map.of("ab/cd+ef==", key);

        store.addProjectKeys(projectKeys, Map.of());

        assertEquals("ab/cd+ef==", key.getKey());
        Future<ApiKeyData> hit = store.getApiKeyData("ab/cd+ef==", null);
        assertNotNull(hit.result());
        assertEquals(key, hit.result().getOriginalKey());
    }

    @Test
    public void testAddProjectKeysApiManaged() {
        Key key = new Key();
        key.setProject("prj1");
        key.setRole("role1");
        key.setKey("api-secret");
        Map<String, Key> projectKeys = Map.of("human-name", key);

        store.addProjectKeys(Map.of(), projectKeys);

        assertEquals("api-secret", key.getKey());
    }

    @Test
    public void testAddProjectKeysApiManagedAuthLookup() {
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Key key = new Key();
        key.setProject("prj1");
        key.setRole("role1");
        key.setKey("api-secret");
        Map<String, Key> projectKeys = Map.of("human-name", key);

        store.addProjectKeys(Map.of(), projectKeys);

        Future<ApiKeyData> hit = store.getApiKeyData("api-secret", null);
        assertNotNull(hit.result());
        assertEquals(key, hit.result().getOriginalKey());
        assertNull(store.getApiKeyData("human-name", null).result());
    }

    @Test
    public void testAddApiProjectKeyBlankSecretFailsClosed() {
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Key key = new Key();
        key.setProject("prj1");
        key.setRole("role1");
        // API-sourced entry with no secret after decrypt — must be skipped, never back-filled
        // from the canonical-id map key (fail closed).
        Map<String, Key> projectKeys = Map.of("keys/platform/foo", key);

        store.addProjectKeys(Map.of(), projectKeys);

        assertNull(key.getKey());
        assertNull(store.getApiKeyData("keys/platform/foo", null).result());
    }

    @Test
    public void testAddOrUpdateKey() {
        Key key = new Key();
        key.setProject("prj1");
        key.setRole("role1");
        key.setKey("fast-secret");
        ApiKeyData data = new ApiKeyData();
        data.setOriginalKey(key);

        store.addOrUpdateKey("fast-secret", data);

        Future<ApiKeyData> hit = store.getApiKeyData("fast-secret", null);
        assertNotNull(hit.result());
        assertEquals(data, hit.result());
    }

    @Test
    public void testRemoveKey() {
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Key key = new Key();
        key.setProject("prj1");
        key.setRole("role1");
        key.setKey("removable");
        ApiKeyData data = new ApiKeyData();
        data.setOriginalKey(key);
        store.addOrUpdateKey("removable", data);

        store.removeKey("removable");

        assertTrue(store.getApiKeyData("removable", null).failed());
    }

    @Test
    public void testGetApiKeyData() {
        ApiKeyData apiKeyData = new ApiKeyData();
        store.assignPerRequestApiKey(apiKeyData);

        assertNotNull(apiKeyData.getPerRequestKey());

        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<ApiKeyData> res1  = store.getApiKeyData(apiKeyData.getPerRequestKey(), null);
        assertNotNull(res1);
        assertEquals(apiKeyData, res1.result());

        assertTrue(store.getApiKeyData("unknown-key", null).failed());
    }

    @Test
    public void testRestrictApiKeyData() {
        String json = """
                {
                  "keys": {
                        "restrictedKey": {
                             "project": "test",
                             "role": "default",
                             "allowedIpAddressRanges": ["198.51.100.14/24", "2002::1234:abcd:ffff:c0a8:101/64"]
                        },
                        "key": {
                             "project": "test",
                             "role": "default"
                        },
                        "forbiddenKey": {
                             "project": "test",
                             "role": "default",
                             "allowedIpAddressRanges": []
                        }
                  }
                }
                """;
        Config config = ProxyUtil.convertToObject(json, Config.class);
        assertNotNull(config);
        store.addProjectKeys(config.getKeys(), Map.of());

        List<String> allowedIpAddresses = List.of("198.51.100.25", "2002:0000:0000:1234:0030:1500:0340:0000");
        List<String> forbiddenIpAddresses = List.of("198.51.99.14", "2002:0000:0000:1233:0000:FB00:0000:0000");

        for (String ip : allowedIpAddresses) {
            Future<ApiKeyData> res = store.getApiKeyData("restrictedKey", ip);
            assertNotNull(res);
            assertTrue(res.succeeded());
            assertEquals("restrictedKey", res.result().getOriginalKey().getKey());
        }

        for (String ip : forbiddenIpAddresses) {
            Future<ApiKeyData> res = store.getApiKeyData("restrictedKey", ip);
            assertNotNull(res);
            assertTrue(res.failed());
            HttpException exception = (HttpException) res.cause();
            assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
            assertTrue(exception.getMessage().contains(ip));
        }

        List<String> allIps = Stream.concat(allowedIpAddresses.stream(), forbiddenIpAddresses.stream()).toList();

        // no restrictions applied to the key `key`
        for (String ip : allIps) {
            Future<ApiKeyData> res = store.getApiKeyData("key", ip);
            assertNotNull(res);
            assertTrue(res.succeeded());
            assertEquals("key", res.result().getOriginalKey().getKey());
        }

        // the key is forbidden for any client
        for (String ip : allIps) {
            Future<ApiKeyData> res = store.getApiKeyData("forbiddenKey", ip);
            assertNotNull(res);
            assertTrue(res.failed());
            HttpException exception = (HttpException) res.cause();
            assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
            assertTrue(exception.getMessage().contains(ip));
        }
    }

    @Test
    public void testInvalidateApiKey() {
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });
        ApiKeyData apiKeyData = new ApiKeyData();
        store.assignPerRequestApiKey(apiKeyData);

        assertNotNull(apiKeyData.getPerRequestKey());

        store.invalidatePerRequestApiKey(apiKeyData);

        assertTrue(store.getApiKeyData(apiKeyData.getPerRequestKey(), null).failed());
    }

    @Test
    public void testUpdateApiKey() {
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
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

        Future<ApiKeyData> res1  = store.getApiKeyData(apiKeyData.getPerRequestKey(), null);
        assertNotNull(res1);
        assertEquals(ref.getValue(), res1.result());
    }
}

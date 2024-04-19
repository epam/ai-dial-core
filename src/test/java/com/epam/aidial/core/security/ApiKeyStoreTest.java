package com.epam.aidial.core.security;

import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.service.LockService;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.storage.BlobStorage;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
public class ApiKeyStoreTest {

    private static RedisServer redisServer;

    private static RedissonClient redissonClient;

    @Mock
    private Vertx vertx;

    @Mock
    private BlobStorage blobStorage;

    private ApiKeyStore store;

    @BeforeAll
    public static void beforeAll() throws IOException {
        redisServer = RedisServer.builder()
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
    public static void afterAll() {
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
        store = new ApiKeyStore(resourceService, lockService);
    }

    @Test
    public void testAssignApiKey() {
        ApiKeyData apiKeyData = new ApiKeyData();
        store.assignApiKey(apiKeyData);
        assertNotNull(apiKeyData.getPerRequestKey());
    }

    @Test
    public void testAddProjectKeys() {

        Key key1 = new Key();
        key1.setProject("prj1");
        key1.setRole("role1");
        Map<String, Key> projectKeys1 = Map.of("key1", key1);

        store.addProjectKeys(projectKeys1);

        ApiKeyData apiKeyData = new ApiKeyData();
        store.assignApiKey(apiKeyData);

        Key key2 = new Key();
        key1.setProject("prj1");
        key1.setRole("role1");
        Map<String, Key> projectKeys2 = Map.of("key2", key2);

        store.addProjectKeys(projectKeys2);

        // old key must be removed
        assertNull(store.getApiKeyData("key1"));
        // new key must be accessed
        ApiKeyData res1 = store.getApiKeyData("key2");
        assertNotNull(res1);
        assertEquals(key2, res1.getOriginalKey());
        // existing per request key must be accessed
        assertNotNull(store.getApiKeyData(apiKeyData.getPerRequestKey()));

    }

    @Test
    public void testGetApiKeyData() {
        ApiKeyData apiKeyData = new ApiKeyData();
        store.assignApiKey(apiKeyData);

        assertNotNull(apiKeyData.getPerRequestKey());

        ApiKeyData res1  = store.getApiKeyData(apiKeyData.getPerRequestKey());
        assertEquals(apiKeyData, res1);

        assertNull(store.getApiKeyData("unknown-key"));
    }

    @Test
    public void testInvalidateApiKey() {
        ApiKeyData apiKeyData = new ApiKeyData();
        store.assignApiKey(apiKeyData);

        assertNotNull(apiKeyData.getPerRequestKey());

        store.invalidateApiKey(apiKeyData);

        assertNull(store.getApiKeyData(apiKeyData.getPerRequestKey()));
    }
}

package com.epam.aidial.core.security;

import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.config.Key;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ApiKeyStoreTest {

    private ApiKeyStore store;

    @BeforeEach
    public void beforeEach() {
        store = new ApiKeyStore();
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

        assertNull(store.getApiKeyData("key1"));
        ApiKeyData res1 = store.getApiKeyData("key2");
        assertNotNull(res1);
        assertEquals(key2, res1.getOriginalKey());

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

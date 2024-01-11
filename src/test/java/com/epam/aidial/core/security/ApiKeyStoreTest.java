package com.epam.aidial.core.security;

import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.config.Key;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    public void testAddProjectKey() {

        Key key = new Key();
        key.setKey("key1");
        key.setProject("prj1");
        key.setRole("role1");

        store.addProjectKey(key);

        ApiKeyData apiKeyData = store.getApiKeyData("key1");
        assertNotNull(apiKeyData);
        assertEquals(key, apiKeyData.getOriginalKey());
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

package com.epam.aidial.core.security;

import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.config.Key;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

import static com.epam.aidial.core.security.ApiKeyGenerator.generateKey;

@Slf4j
public class ApiKeyStore {

    private final Map<String, ApiKeyData> keys = new HashMap<>();

    public synchronized void assignApiKey(ApiKeyData data) {
        String apiKey = generateApiKey();
        keys.put(apiKey, data);
        data.setPerRequestKey(apiKey);
    }

    public synchronized ApiKeyData getApiKeyData(String key) {
        return keys.get(key);
    }

    public synchronized void invalidateApiKey(ApiKeyData apiKeyData) {
        if (apiKeyData.getPerRequestKey() != null) {
            keys.remove(apiKeyData.getPerRequestKey());
        }
    }

    public synchronized void addProjectKey(Key key) {
        String apiKey = key.getKey();
        if (keys.containsKey(apiKey)) {
            apiKey = generateApiKey();
        }
        key.setKey(apiKey);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(key);
        keys.put(apiKey, apiKeyData);
    }

    private String generateApiKey() {
        String apiKey = generateKey();
        while (keys.containsKey(apiKey)) {
            log.warn("duplicate API key is found. Trying to generate a new one");
            apiKey = generateKey();
        }
        return apiKey;
    }

}

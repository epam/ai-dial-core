package com.epam.aidial.core.security;

import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.service.LockService;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.concurrent.GuardedBy;

import static com.epam.aidial.core.security.ApiKeyGenerator.generateKey;
import static com.epam.aidial.core.storage.BlobStorageUtil.PATH_SEPARATOR;

@Slf4j
@AllArgsConstructor
public class ApiKeyStore {

    public static final String API_KEY_DATA_BUCKET = "api_key_data";
    public static final String API_KEY_DATA_LOCATION = API_KEY_DATA_BUCKET + PATH_SEPARATOR;

    private final ResourceService resourceService;

    private final LockService lockService;

    private final Vertx vertx;

    /**
     * API keys are captured from secure storage.
     */
    @GuardedBy("this")
    private final Map<String, ApiKeyData> keys = new HashMap<>();

    public synchronized void assignApiKey(ApiKeyData data) {
        lockService.underBucketLock(API_KEY_DATA_LOCATION, () -> {
            ResourceDescription resource = generateApiKey();
            String apiKey = resource.getName();
            data.setPerRequestKey(apiKey);
            String json = ProxyUtil.convertToString(data);
            if (resourceService.putResource(resource, json, false, false) == null) {
                throw new IllegalStateException(String.format("API key %s already exists in the storage", apiKey));
            }
            return apiKey;
        });
    }

    public synchronized Future<ApiKeyData> getApiKeyData(String key) {
        ApiKeyData apiKeyData = keys.get(key);
        if (apiKeyData != null) {
            return Future.succeededFuture(apiKeyData);
        }
        ResourceDescription resource = toResource(key);
        return vertx.executeBlocking(() -> ProxyUtil.convertToObject(resourceService.getResource(resource), ApiKeyData.class));
    }

    public Future<Boolean> invalidateApiKey(ApiKeyData apiKeyData) {
        String apiKey = apiKeyData.getPerRequestKey();
        if (apiKey != null) {
            ResourceDescription resource = toResource(apiKey);
            return vertx.executeBlocking(() -> resourceService.deleteResource(resource));
        }
        return Future.succeededFuture(true);
    }

    public synchronized void addProjectKeys(Map<String, Key> projectKeys) {
        keys.clear();
        lockService.underBucketLock(API_KEY_DATA_LOCATION, () -> {
            for (Map.Entry<String, Key> entry : projectKeys.entrySet()) {
                String apiKey = entry.getKey();
                Key value = entry.getValue();
                ResourceDescription resource = toResource(apiKey);
                if (resourceService.hasResource(resource)) {
                    resource = generateApiKey();
                    apiKey = resource.getName();
                }
                value.setKey(apiKey);
                ApiKeyData apiKeyData = new ApiKeyData();
                apiKeyData.setOriginalKey(value);
                keys.put(apiKey, apiKeyData);
            }
            return null;
        });
    }

    public void updateApiKeyData(ApiKeyData apiKeyData) {
        String apiKey = apiKeyData.getPerRequestKey();
        if (apiKey == null) {
            return;
        }
        String json = ProxyUtil.convertToString(apiKeyData);
        ResourceDescription resource = toResource(apiKey);
        resourceService.putResource(resource, json, true, false);
    }

    private ResourceDescription generateApiKey() {
        String apiKey = generateKey();
        ResourceDescription resource = toResource(apiKey);
        while (resourceService.hasResource(resource) || keys.containsKey(apiKey)) {
            log.warn("duplicate API key is found. Trying to generate a new one");
            apiKey = generateKey();
            resource = toResource(apiKey);
        }
        return resource;
    }

    private static ResourceDescription toResource(String apiKey) {
        return ResourceDescription.fromDecoded(
                ResourceType.API_KEY_DATA, API_KEY_DATA_BUCKET, API_KEY_DATA_LOCATION, apiKey);
    }

}

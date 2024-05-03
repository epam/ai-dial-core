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

import static com.epam.aidial.core.security.ApiKeyGenerator.generateKey;
import static com.epam.aidial.core.storage.BlobStorageUtil.PATH_SEPARATOR;

/**
 * The store keeps per request and project API key data.
 * <p>
 *     Per request key is assigned during the request and terminated in the end of the request.
 *     Project keys are hosted by external secure storage and might be periodically updated by {@link com.epam.aidial.core.config.FileConfigStore}.
 * </p>
 */
@Slf4j
@AllArgsConstructor
public class ApiKeyStore {

    public static final String API_KEY_DATA_BUCKET = "api_key_data";
    public static final String API_KEY_DATA_LOCATION = API_KEY_DATA_BUCKET + PATH_SEPARATOR;

    private final ResourceService resourceService;

    private final LockService lockService;

    private final Vertx vertx;

    /**
     * Project API keys are hosted in the secure storage.
     */
    private final Map<String, ApiKeyData> keys = new HashMap<>();

    /**
     * Assigns a new generated per request key to the {@link ApiKeyData}.
     * <p>
     *     Note. The method is blocking and shouldn't be run in the event loop thread.
     * </p>
     */
    public synchronized void assignPerRequestApiKey(ApiKeyData data) {
//        lockService.underBucketLock(API_KEY_DATA_LOCATION, () -> {
        ResourceDescription resource = generateApiKey();
        String apiKey = resource.getName();
        data.setPerRequestKey(apiKey);
        String json = ProxyUtil.convertToString(data);
        if (resourceService.putResource(resource, json, false, false) == null) {
            throw new IllegalStateException(String.format("API key %s already exists in the storage", apiKey));
        }
//            return apiKey;
//        });
    }

    /**
     * Returns API key data for the given key.
     *
     * @param key API key could be either project or per request key.
     * @return the future of data associated with the given key.
     */
    public synchronized Future<ApiKeyData> getApiKeyData(String key) {
        ApiKeyData apiKeyData = keys.get(key);
        if (apiKeyData != null) {
            return Future.succeededFuture(apiKeyData);
        }
        ResourceDescription resource = toResource(key);
        return vertx.executeBlocking(() -> ProxyUtil.convertToObject(resourceService.getResource(resource), ApiKeyData.class));
    }

    /**
     * Invalidates per request API key.
     * If api key belongs to a project the operation will not have affect.
     *
     * @param apiKeyData associated with the key to be invalidated.
     * @return the future of the invalidation result: <code>true</code> means the key is successfully invalidated.
     */
    public Future<Boolean> invalidatePerRequestApiKey(ApiKeyData apiKeyData) {
        String apiKey = apiKeyData.getPerRequestKey();
        if (apiKey != null) {
            ResourceDescription resource = toResource(apiKey);
            return vertx.executeBlocking(() -> resourceService.deleteResource(resource));
        }
        return Future.succeededFuture(true);
    }

    /**
     * Adds new project keys from the secure storage and removes previous project keys if any.
     * <p>
     *     Note. The method is blocking and shouldn't be run in the event loop thread.
     * </p>
     *
     * @param projectKeys new projects to be added to the store.
     */
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

    /**
     * Updates data associated with per request key.
     * If api key belongs to a project the operation will not have affect.
     *
     * @param apiKeyData per request key data.
     */
    public void updatePerRequestApiKeyData(ApiKeyData apiKeyData) {
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

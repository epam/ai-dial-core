package com.epam.aidial.core.server.security;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.config.FileConfigStore;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ResourceType;
import com.epam.aidial.core.server.service.ResourceService;
import com.epam.aidial.core.server.storage.ResourceDescription;
import com.epam.aidial.core.server.util.EtagHeader;
import com.epam.aidial.core.server.util.HttpException;
import com.epam.aidial.core.server.util.HttpStatus;
import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static com.epam.aidial.core.server.security.ApiKeyGenerator.generateKey;
import static com.epam.aidial.core.server.storage.BlobStorageUtil.PATH_SEPARATOR;

/**
 * The store keeps per request and project API key data.
 * <p>
 *     Per request key is assigned during the request and terminated in the end of the request.
 *     Project keys are hosted by external secure storage and might be periodically updated by {@link FileConfigStore}.
 * </p>
 */
@Slf4j
public class ApiKeyStore {

    public static final String API_KEY_DATA_BUCKET = "api_key_data";
    public static final String API_KEY_DATA_LOCATION = API_KEY_DATA_BUCKET + PATH_SEPARATOR;

    private final ResourceService resourceService;

    private final Vertx vertx;

    public ApiKeyStore(ResourceService resourceService, Vertx vertx) {
        this.resourceService = resourceService;
        this.vertx = vertx;
    }

    /**
     * Project API keys are hosted in the secure storage.
     */
    private volatile Map<String, ApiKeyData> keys = new HashMap<>();

    /**
     * Assigns a new generated per request key to the {@link ApiKeyData}.
     * <p>
     *     Note. The method is blocking and shouldn't be run in the event loop thread.
     * </p>
     */
    public void assignPerRequestApiKey(ApiKeyData data) {
        String perRequestKey = generateKey();
        ResourceDescription resource = toResource(perRequestKey);
        data.setPerRequestKey(perRequestKey);
        String json = ProxyUtil.convertToString(data);
        try {
            resourceService.putResource(resource, json, EtagHeader.NEW_ONLY, false);
        } catch (HttpException exception) {
            throw exception.getStatus() == HttpStatus.PRECONDITION_FAILED
                    ? new IllegalStateException(String.format("API key %s already exists in the storage", perRequestKey))
                    : exception;
        }
    }

    public Future<Void> updatePerRequestApiKey(String key, Function<String, String> fn) {
        if (key == null) {
            IllegalArgumentException error = new IllegalArgumentException("Per request API key is undefined");
            log.error("Error occurred at updating api key data: per request API key is undefined");
            return Future.failedFuture(error);
        }
        ResourceDescription resource = toResource(key);
        return vertx.executeBlocking(() -> {
            resourceService.computeResource(resource, fn);
            return null;
        }, false);
    }

    /**
     * Returns API key data for the given key.
     *
     * @param key API key could be either project or per request key.
     * @return the future of data associated with the given key.
     */
    public Future<ApiKeyData> getApiKeyData(String key) {
        ApiKeyData apiKeyData = keys.get(key);
        if (apiKeyData != null) {
            return Future.succeededFuture(apiKeyData);
        }
        ResourceDescription resource = toResource(key);
        return vertx.executeBlocking(() -> ProxyUtil.convertToObject(resourceService.getResource(resource), ApiKeyData.class), false).compose(result -> {
            if (result == null) {
                return Future.failedFuture(new HttpException(HttpStatus.UNAUTHORIZED, "Unknown api key"));
            }
            return Future.succeededFuture(result);
        });
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
            return vertx.executeBlocking(() -> resourceService.deleteResource(resource, EtagHeader.ANY), false);
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
    public void addProjectKeys(Map<String, Key> projectKeys) {
        Map<String, ApiKeyData> apiKeyDataMap = new HashMap<>();
        for (Map.Entry<String, Key> entry : projectKeys.entrySet()) {
            String apiKey = entry.getKey();
            Key value = entry.getValue();
            validateProjectKey(value);
            value.setKey(apiKey);
            ApiKeyData apiKeyData = new ApiKeyData();
            apiKeyData.setOriginalKey(value);
            apiKeyDataMap.put(apiKey, apiKeyData);
        }
        keys = apiKeyDataMap;
    }

    private void validateProjectKey(Key key) {
        if (StringUtils.isEmpty(key.getProject())) {
            throw new IllegalArgumentException("Project key is undefined");
        }
        if (StringUtils.isEmpty(key.getRole()) && (key.getRoles() == null || key.getRoles().isEmpty())) {
            throw new IllegalArgumentException("Invalid key: at least one role must be assigned to the key " + key.getProject());
        }
    }

    private static ResourceDescription toResource(String apiKey) {
        return ResourceDescription.fromDecoded(
                ResourceType.API_KEY_DATA, API_KEY_DATA_BUCKET, API_KEY_DATA_LOCATION, apiKey);
    }

}

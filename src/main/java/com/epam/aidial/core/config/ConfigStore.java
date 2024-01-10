package com.epam.aidial.core.config;

public interface ConfigStore {

    /**
     * Allowed to return not up-to-date config for some period of time e.g. 1 min.
     *
     * @return immutable config.
     */
    Config load();

    /**
     * Gets data associated with the API key.
     */
    ApiKeyData getApiKeyData(String key);

    /**
     * Assigns a new API key to the data.
     */
    void assignApiKey(ApiKeyData data);

    /**
     * Invalidates the API key associated with the given data.
     */
    void invalidateApiKey(ApiKeyData apiKeyData);

}

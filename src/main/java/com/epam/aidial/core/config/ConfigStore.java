package com.epam.aidial.core.config;

public interface ConfigStore {

    /**
     * Allowed to return not up-to-date config for some period of time e.g. 1 min.
     *
     * @return immutable config.
     */
    Config load();

}

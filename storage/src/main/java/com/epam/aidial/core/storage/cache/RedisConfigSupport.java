package com.epam.aidial.core.storage.cache;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.redisson.config.ConfigSupport;

public class RedisConfigSupport extends ConfigSupport {

    public RedisConfigSupport() {
        super();
        jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}

package com.epam.aidial.core.storage.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;
import org.redisson.config.Config;
import org.redisson.config.ConfigSupport;

/**
 * Parses the Core {@code redis} settings into a Redisson {@link Config}.
 * Redisson 4 dropped the JSON format, but JSON is a subset of YAML, so the settings are parsed as YAML.
 * <ul>
 *   <li>The Core-specific {@code provider} block is not a Redisson setting and is removed before parsing.</li>
 *   <li>{@code retryInterval} is not accepted by the Redisson 4 YAML parser, so it is applied through its setter.</li>
 *   <li>Unknown settings are rejected, so a setting that Redisson no longer supports is not silently ignored.</li>
 * </ul>
 */
@UtilityClass
public class RedisConfigSupport {

    public static final String PROVIDER_KEY = "provider";
    private static final String RETRY_INTERVAL_KEY = "retryInterval";
    private static final String SINGLE_SERVER_KEY = "singleServerConfig";
    private static final String CLUSTER_SERVERS_KEY = "clusterServersConfig";

    public Config parse(JsonNode conf) {
        ObjectNode redisson = conf.deepCopy();
        redisson.remove(PROVIDER_KEY);
        Integer singleRetryInterval = removeRetryInterval(redisson, SINGLE_SERVER_KEY);
        Integer clusterRetryInterval = removeRetryInterval(redisson, CLUSTER_SERVERS_KEY);

        Config config;
        try {
            config = new ConfigSupport().fromYAML(redisson.toString(), Config.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid redis configuration: " + e.getMessage(), e);
        }

        if (singleRetryInterval != null) {
            config.useSingleServer().setRetryInterval(singleRetryInterval);
        }
        if (clusterRetryInterval != null) {
            config.useClusterServers().setRetryInterval(clusterRetryInterval);
        }
        return config;
    }

    private static Integer removeRetryInterval(ObjectNode root, String serverConfigKey) {
        JsonNode section = root.get(serverConfigKey);
        if (section instanceof ObjectNode object) {
            JsonNode value = object.remove(RETRY_INTERVAL_KEY);
            return value == null ? null : value.asInt();
        }
        return null;
    }
}

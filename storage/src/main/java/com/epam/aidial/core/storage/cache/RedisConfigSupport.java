package com.epam.aidial.core.storage.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;
import org.redisson.config.Config;
import org.redisson.config.ConfigSupport;

import java.util.Map;

/**
 * Parses the Core {@code redis} settings into a Redisson {@link Config}.
 * Redisson 4 dropped the JSON format, but JSON is a subset of YAML, so the settings are parsed as YAML.
 * <ul>
 *   <li>The Core-specific {@code provider} block is not a Redisson setting and is removed before parsing.</li>
 *   <li>{@code retryInterval} is not accepted by the Redisson 4 YAML parser, so it is applied through its setter.</li>
 *   <li>An object setting written as {@code {"class": "a.b.C", ...}} (the Redisson 3 JSON form, e.g. {@code nettyHook}
 *   or {@code codec}) becomes the YAML tag {@code !!a.b.C} that Redisson 4 reads (its {@code !<a.b.C>} form rejects nested classes).</li>
 *   <li>Unknown settings are rejected, so a setting that Redisson no longer supports is not silently ignored.</li>
 * </ul>
 */
@UtilityClass
public class RedisConfigSupport {

    public static final String PROVIDER_KEY = "provider";
    private static final String CLASS_KEY = "class";
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
            config = new ConfigSupport().fromYAML(toYaml(redisson), Config.class);
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

    private static String toYaml(JsonNode node) {
        StringBuilder yaml = new StringBuilder();
        appendYaml(node, yaml);
        return yaml.toString();
    }

    private static void appendYaml(JsonNode node, StringBuilder yaml) {
        if (node.isObject()) {
            JsonNode className = node.get(CLASS_KEY);
            if (className != null && className.isTextual()) {
                yaml.append("!!").append(className.asText()).append(' ');
            }
            yaml.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (field.getValue() == className && className != null && className.isTextual()) {
                    continue;
                }
                if (!first) {
                    yaml.append(',');
                }
                first = false;
                yaml.append(JsonNodeFactory.instance.textNode(field.getKey())).append(':');
                appendYaml(field.getValue(), yaml);
            }
            yaml.append('}');
        } else if (node.isArray()) {
            yaml.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    yaml.append(',');
                }
                appendYaml(node.get(i), yaml);
            }
            yaml.append(']');
        } else {
            yaml.append(node);
        }
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

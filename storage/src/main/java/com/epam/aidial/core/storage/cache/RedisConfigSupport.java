package com.epam.aidial.core.storage.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;
import org.redisson.config.Config;
import org.redisson.config.ConfigSupport;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
        return new Yaml(new TaggedMapRepresenter(options), options).dump(toObject(node));
    }

    private static Object toObject(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                map.put(field.getKey(), toObject(field.getValue()));
            }
            JsonNode className = node.get(CLASS_KEY);
            if (className != null && className.isTextual()) {
                map.remove(CLASS_KEY);
                return new TaggedMap(className.asText(), map);
            }
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(toObject(item)));
            return list;
        }
        if (node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    private record TaggedMap(String className, Map<String, Object> map) {
    }

    private static class TaggedMapRepresenter extends Representer {

        TaggedMapRepresenter(DumperOptions options) {
            super(options);
            representers.put(TaggedMap.class, data -> {
                TaggedMap tagged = (TaggedMap) data;
                return representMapping(new Tag(Tag.PREFIX + tagged.className()), tagged.map(), DumperOptions.FlowStyle.FLOW);
            });
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

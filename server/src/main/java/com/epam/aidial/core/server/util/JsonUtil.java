package com.epam.aidial.core.server.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

@Slf4j
@UtilityClass
public class JsonUtil {

    private static final Configuration CONFIGURATION = Configuration.defaultConfiguration()
            .jsonProvider(new JacksonJsonNodeJsonProvider()).addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL);

    /**
     * Json node or null if required property is missed in the JSON path.
     */
    @Nullable
    public JsonNode read(JsonNode node, String path) {
        try {
            return com.jayway.jsonpath.JsonPath.using(CONFIGURATION).parse(node).read(path, JsonNode.class);
        } catch (PathNotFoundException e) {
            return null;
        } catch (InvalidPathException e) {
            log.warn("Invalid JSON path: {}", path, e);
            return null;
        }
    }

    public Set<String> collectStrings(JsonNode root, List<String> paths, Function<JsonNode, String> mapper) {
        if (paths.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (String path : paths) {
            JsonNode node = JsonUtil.read(root, path);
            collectStrings(result, node, mapper);
        }
        return result;
    }

    private static void collectStrings(Set<String> result, JsonNode node, Function<JsonNode, String> mapper) {
        if (node == null || node.isNull()) {
            return;
        }

        if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (JsonNode item : arrayNode) {
                collectStrings(result, item, mapper);
            }
        } else {
            String value = mapper.apply(node);
            if (value != null) {
                result.add(value);
            }
        }
    }

    public void update(ObjectNode object, String key, Function<JsonNode, JsonNode> mapper) {
        object.set(key, mapper.apply(object.get(key)));
    }

    public JsonNode sort(JsonNode node) {
        if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < node.size(); i++) {
                arrayNode.set(i, sort(arrayNode.get(i)));
            }
        } else if (node.isObject()) {
            ObjectNode result = new ObjectNode(ProxyUtil.MAPPER.getNodeFactory(), new TreeMap<>());
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                result.set(entry.getKey(), sort(entry.getValue()));
            }
            node = result;
        }
        return node;
    }
}

package com.epam.aidial.core.server.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import javax.annotation.Nullable;

@Slf4j
@UtilityClass
public class JsonUtil {

    private static final Configuration CONFIGURATION = Configuration.defaultConfiguration()
            .jsonProvider(new JacksonJsonNodeJsonProvider()).addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL, Option.SUPPRESS_EXCEPTIONS);

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

    public void applyDefault(ObjectNode object, String key, JsonNode defaultValue) {
        object.set(key, copy(object.get(key), defaultValue));
    }

    /**
     * Copies default values to the target node from the source.
     * The default value is copied from the source to the target if it's missed in the target node.
     *
     * <p>
     *     Note. Arrays are not copied.
     * </p>
     */
    private JsonNode copy(JsonNode target, JsonNode source) {
        if (target == null || target.isNull()) {
            return source;
        }
        if (source == null || source.isNull()) {
            return target;
        }
        if (target.getNodeType() != source.getNodeType()) {
            return source;
        }
        if (source.isObject()) {
            return copyObjects((ObjectNode) target, (ObjectNode) source);
        }
        return target;
    }

    private ObjectNode copyObjects(ObjectNode target, ObjectNode source) {
        for (Map.Entry<String, JsonNode> entry : source.properties()) {
            String name = entry.getKey();
            target.set(name, copy(target.get(name), entry.getValue()));
        }
        return target;
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

    public JsonNode tryParse(String data) {
        try {
            return ProxyUtil.MAPPER.readTree(data);
        } catch (JsonProcessingException ignore) {
            return MissingNode.getInstance();
        }
    }

    public JsonNode tryParse(byte[] data) {
        try {
            return ProxyUtil.MAPPER.readTree(data);
        } catch (IOException ignore) {
            return MissingNode.getInstance();
        }
    }

    @SneakyThrows
    public byte[] serialize(JsonNode node) {
        return ProxyUtil.MAPPER.writeValueAsBytes(node);
    }
}

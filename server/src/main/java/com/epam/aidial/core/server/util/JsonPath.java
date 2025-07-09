package com.epam.aidial.core.server.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import lombok.experimental.UtilityClass;

import javax.annotation.Nullable;

@UtilityClass
public class JsonPath {

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
        }
    }
}

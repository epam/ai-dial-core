package com.epam.aidial.core.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that SpecJsonConverter produces JSON equivalent to the YAML spec, in a stable format.
 */
class SpecJsonConverterTest {

    private static final String YAML = """
            openapi: 3.0.0
            paths:
              /z/path:
                get:
                  operationId: z
              /a/path:
                get:
                  operationId: a
            components:
              schemas:
                Claims:
                  type: object
                  additionalProperties: {}
                  required: []
                  properties:
                    parentPath:
                      type: string
                      nullable: true
                    limit:
                      type: integer
                      maximum: 1000
            """;

    @Test
    void jsonIsEquivalentToYaml() throws Exception {
        JsonNode fromYaml = new ObjectMapper(new YAMLFactory()).readTree(YAML);
        JsonNode fromJson = new ObjectMapper().readTree(SpecJsonConverter.convert(YAML));

        assertEquals(fromYaml, fromJson);
    }

    @Test
    void keyOrderIsPreserved() throws Exception {
        JsonNode paths = new ObjectMapper().readTree(SpecJsonConverter.convert(YAML)).get("paths");
        List<String> keys = new ArrayList<>();
        paths.fieldNames().forEachRemaining(keys::add);

        assertEquals(List.of("/z/path", "/a/path"), keys);
    }

    @Test
    void outputFormatIsPlatformIndependent() throws Exception {
        String json = SpecJsonConverter.convert(YAML);

        assertFalse(json.contains("\r"));
        assertTrue(json.endsWith("}\n"));
        assertTrue(json.contains("\n  \"openapi\": \"3.0.0\""));
        assertTrue(json.contains("\"additionalProperties\": {}"));
        assertTrue(json.contains("\"required\": []"));
    }
}

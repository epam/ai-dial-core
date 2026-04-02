package com.epam.aidial.core.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;

import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class DtoSchemaGenerator {

    private static final String DEFS_KEY = "$defs";
    private static final String REF_KEY = "$ref";
    private static final String DEFS_PREFIX = "#/$defs/";
    private static final String COMPONENTS_PREFIX = "#/components/schemas/";

    private final SchemaGenerator generator;
    private final Map<String, ObjectNode> schemas = new LinkedHashMap<>();

    public DtoSchemaGenerator() {
        JacksonModule jacksonModule = new JacksonModule(
                JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE
        );

        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2020_12,
                OptionPreset.PLAIN_JSON
        );

        configBuilder.with(jacksonModule);
        configBuilder.with(Option.DEFINITIONS_FOR_ALL_OBJECTS);
        configBuilder.without(Option.SCHEMA_VERSION_INDICATOR);

        configBuilder.forTypesInGeneral().withCustomDefinitionProvider((javaType, context) -> {
            if (javaType.isInstanceOf(Map.class)) {
                var typeParams = javaType.getTypeParameters();
                if (typeParams != null && typeParams.size() == 2) {
                    var valueType = typeParams.get(1);
                    ObjectNode schema = context.getGeneratorConfig().createObjectNode();
                    schema.put("type", "object");
                    schema.set("additionalProperties",
                            context.createDefinitionReference(valueType));
                    return new com.github.victools.jsonschema.generator
                            .CustomDefinition(schema);
                }
            }
            return null;
        });

        SchemaGeneratorConfig config = configBuilder.build();
        this.generator = new SchemaGenerator(config);
    }

    public void processType(Type type) {
        if (type == null || type == Void.class) {
            return;
        }

        JsonNode schema = generator.generateSchema(type);
        if (!(schema instanceof ObjectNode rootNode)) {
            return;
        }

        // Extract $defs into the shared schemas map
        if (rootNode.has(DEFS_KEY)) {
            ObjectNode defs = (ObjectNode) rootNode.get(DEFS_KEY);
            Iterator<Map.Entry<String, JsonNode>> fields = defs.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (entry.getValue() instanceof ObjectNode defNode) {
                    ObjectNode fixedNode = fixRefPaths(defNode);
                    schemas.putIfAbsent(sanitizeSchemaName(entry.getKey()), fixedNode);
                }
            }
            rootNode.remove(DEFS_KEY);
        }

        // Fix $ref paths in the main schema
        ObjectNode fixedRoot = fixRefPaths(rootNode);

        // Determine the schema name from the type
        String schemaName = resolveTypeName(type);
        schemas.putIfAbsent(schemaName, fixedRoot);
    }

    public Map<String, ObjectNode> getSchemas() {
        return new LinkedHashMap<>(schemas);
    }

    public String resolveTypeName(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz.getSimpleName();
        }
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            Class<?> rawType = (Class<?>) pt.getRawType();
            StringBuilder sb = new StringBuilder(rawType.getSimpleName());
            Type[] typeArgs = pt.getActualTypeArguments();
            for (Type arg : typeArgs) {
                sb.append(resolveTypeName(arg));
            }
            return sb.toString();
        }
        return type.getTypeName().replaceAll("[^a-zA-Z0-9]", "");
    }

    private ObjectNode fixRefPaths(ObjectNode node) {
        return (ObjectNode) fixRefPathsRecursive(node);
    }

    private JsonNode fixRefPathsRecursive(JsonNode node) {
        if (node.isObject()) {
            ObjectNode objNode = (ObjectNode) node;
            if (objNode.has(REF_KEY)) {
                String refValue = objNode.get(REF_KEY).asText();
                if (refValue.startsWith(DEFS_PREFIX)) {
                    String schemaName = sanitizeSchemaName(refValue.substring(DEFS_PREFIX.length()));
                    objNode.set(REF_KEY, new TextNode(COMPONENTS_PREFIX + schemaName));
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = objNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                entry.setValue(fixRefPathsRecursive(entry.getValue()));
            }
            return objNode;
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int ii = 0; ii < arrayNode.size(); ii++) {
                arrayNode.set(ii, fixRefPathsRecursive(arrayNode.get(ii)));
            }
            return arrayNode;
        }
        return node;
    }

    static String sanitizeSchemaName(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "");
    }
}

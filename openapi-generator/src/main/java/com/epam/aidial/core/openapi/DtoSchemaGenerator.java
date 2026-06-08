package com.epam.aidial.core.openapi;

import com.fasterxml.classmate.ResolvedType;
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

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class DtoSchemaGenerator {

    private static final String DEFS_KEY = "$defs";
    private static final String REF_KEY = "$ref";
    private static final String DEFS_PREFIX = "#/$defs/";
    private static final String COMPONENTS_PREFIX = "#/components/schemas/";
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-zA-Z0-9]");

    private final SchemaGenerator generator;
    private final Map<String, ObjectNode> schemas = new LinkedHashMap<>();

    private final ExternalSchemaRegistry externalSchemaRegistry = new ExternalSchemaRegistry(schemas);

    public DtoSchemaGenerator() {
        JacksonModule jacksonModule = new JacksonModule(
                JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE
        );

        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2020_12,
                OptionPreset.PLAIN_JSON
        );
        configBuilder.forTypesInGeneral()
                .withDefinitionNamingStrategy((key, context) -> {
                    ResolvedType type = key.getType();
                    Class<?> clazz = type.getErasedType();
                    if (Map.class.isAssignableFrom(clazz)) {
                        StringBuilder sb = new StringBuilder(clazz.getSimpleName());
                        for (ResolvedType param : type.getTypeParameters()) {
                            sb.append(buildSchemaName(param.getErasedType()));
                        }
                        return sb.toString();
                    }
                    return buildSchemaName(clazz);
                });
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

    public Map<String, ObjectNode> getSchemas() {
        return new LinkedHashMap<>(schemas);
    }

    public void processType(Type type) {
        if (type == null || type == Void.class) {
            return;
        }
        if (type instanceof Class<?> clazz && OpenApiParameterBuilder.isInlinePrimitiveType(clazz)) {
            return;
        }
        JsonNode schema = generator.generateSchema(type);
        if (!(schema instanceof ObjectNode rootNode)) {
            return;
        }
        registerDefinitions(rootNode);
        // Fix $ref paths in the main schema
        ObjectNode fixedRoot = fixRefPaths(rootNode);
        // Determine the schema name from the type
        String schemaName = resolveTypeName(type);
        schemas.putIfAbsent(schemaName, fixedRoot);
    }

    public String resolveTypeName(Type type) {
        if (type instanceof Class<?> clazz) {
            return buildSchemaName(clazz);
        }
        if (type instanceof ParameterizedType pt) {
            Class<?> rawType = (Class<?>) pt.getRawType();
            StringBuilder sb = new StringBuilder(buildSchemaName(rawType));
            Type[] typeArgs = pt.getActualTypeArguments();
            for (Type arg : typeArgs) {
                sb.append(resolveTypeName(arg));
            }
            return sb.toString();
        }
        return sanitizeSchemaName(type.getTypeName());
    }

    public void registerExternalSchema(String schemaName) {
        externalSchemaRegistry.register(schemaName);
    }

    private void registerDefinitions(ObjectNode rootNode) {
        // Extract $defs into the shared schemas map
        if (!rootNode.has(DEFS_KEY)) {
            return;
        }
        ObjectNode defs = (ObjectNode) rootNode.get(DEFS_KEY);
        defs.fields().forEachRemaining(entry -> {
            if (entry.getValue() instanceof ObjectNode defNode) {
                schemas.putIfAbsent(
                        sanitizeSchemaName(entry.getKey()),
                        fixRefPaths(defNode)
                );
            }
        });
        rootNode.remove(DEFS_KEY);
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
        return NON_ALPHANUMERIC.matcher(name).replaceAll("");
    }

    private String buildSchemaName(Class<?> clazz) {
        if (clazz.getEnclosingClass() != null) {
            return clazz.getEnclosingClass().getSimpleName() + clazz.getSimpleName();
        }
        return clazz.getSimpleName();
    }
}
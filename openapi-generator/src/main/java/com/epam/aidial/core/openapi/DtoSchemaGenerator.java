package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.ApiSubType;
import com.epam.aidial.core.openapi.annotations.ApiSubTypes;
import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerationContext;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class DtoSchemaGenerator {

    private static final String DEFS_KEY = "$defs";
    private static final String REF_KEY = "$ref";
    private static final String DEFS_PREFIX = "#/$defs/";
    private static final String COMPONENTS_PREFIX = "#/components/schemas/";
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-zA-Z0-9_\\-]");

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
        configBuilder.forTypesInGeneral().withDefinitionNamingStrategy((key, context) -> {
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
            CustomDefinition definition = createMapSchemaDefinition(javaType, context);
            if (definition != null) {
                return definition;
            }
            return createPolymorphicDefinition(javaType, context);
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
        if (type instanceof Class<?> clazz) {
            ApiSubTypes subTypes = clazz.getAnnotation(ApiSubTypes.class);
            if (subTypes != null) {
                for (ApiSubType subtype : subTypes.value()) {
                    processType(subtype.type());
                }
            }
        }
        JsonNode schema = generator.generateSchema(type);
        if (!(schema instanceof ObjectNode rootNode)) {
            return;
        }
        registerDefinitions(rootNode);
        ObjectNode fixedRoot = fixRefPaths(rootNode);
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

    private CustomDefinition createMapSchemaDefinition(ResolvedType javaType, SchemaGenerationContext context) {
        if (!javaType.isInstanceOf(Map.class)) {
            return null;
        }

        var params = javaType.getTypeParameters();
        if (params == null || params.size() != 2) {
            return null;
        }

        ObjectNode schema = context.getGeneratorConfig().createObjectNode();
        schema.put("type", "object");
        schema.set("additionalProperties", context.createDefinitionReference(params.get(1)));

        return new CustomDefinition(schema);
    }

    private CustomDefinition createPolymorphicDefinition(ResolvedType javaType, SchemaGenerationContext context) {
        Class<?> clazz = javaType.getErasedType();
        ApiSubTypes subTypes = clazz.getAnnotation(ApiSubTypes.class);
        if (subTypes == null) {
            return null;
        }

        if (subTypes.value().length == 1) {
            Class<?> targetImpl = subTypes.value()[0].type();
            ResolvedType implType = context.getTypeContext().resolve(targetImpl);

            return new CustomDefinition(context.createDefinitionReference(implType));
        }

        ObjectNode schema = context.getGeneratorConfig().createObjectNode();
        schema.set("oneOf", createOneOf(subTypes, context));
        schema.set("required", createRequired(subTypes, context));
        schema.set("discriminator", createDiscriminator(subTypes, context));
        return new CustomDefinition(schema);
    }

    private ArrayNode createOneOf(ApiSubTypes subTypes, SchemaGenerationContext context) {
        ArrayNode oneOf = context.getGeneratorConfig().createArrayNode();

        for (ApiSubType subtype : subTypes.value()) {
            ObjectNode ref = context.getGeneratorConfig().createObjectNode();
            ref.put(REF_KEY, COMPONENTS_PREFIX + buildSchemaName(subtype.type()));
            oneOf.add(ref);
        }
        return oneOf;
    }

    private ArrayNode createRequired(ApiSubTypes subTypes, SchemaGenerationContext context) {
        ArrayNode required = context.getGeneratorConfig().createArrayNode();
        required.add(subTypes.discriminatorProperty());
        return required;
    }

    private ObjectNode createDiscriminator(ApiSubTypes subTypes, SchemaGenerationContext context) {
        ObjectNode discriminator = context.getGeneratorConfig().createObjectNode();
        discriminator.put("propertyName", subTypes.discriminatorProperty());
        ObjectNode mapping = context.getGeneratorConfig().createObjectNode();
        for (ApiSubType subtype : subTypes.value()) {
            mapping.put(subtype.discriminatorValue(), COMPONENTS_PREFIX + buildSchemaName(subtype.type()));
        }
        discriminator.set("mapping", mapping);
        return discriminator;
    }

    private void registerDefinitions(ObjectNode rootNode) {
        if (!rootNode.has(DEFS_KEY)) {
            return;
        }
        ObjectNode defs = (ObjectNode) rootNode.get(DEFS_KEY);
        List<String> fieldNames = new ArrayList<>();
        defs.fieldNames().forEachRemaining(fieldNames::add);

        for (String fieldName : fieldNames) {
            JsonNode defNode = defs.get(fieldName);
            if (defNode instanceof ObjectNode objectNode) {
                schemas.putIfAbsent(
                        sanitizeSchemaName(fieldName),
                        fixRefPaths(objectNode)
                );
            }
        }
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

            if (objNode.has("discriminator")) {
                JsonNode discriminator = objNode.get("discriminator");
                if (discriminator.isObject() && discriminator.has("mapping")) {
                    ObjectNode mapping = (ObjectNode) discriminator.get("mapping");
                    List<String> mappingKeys = new ArrayList<>();
                    mapping.fieldNames().forEachRemaining(mappingKeys::add);

                    for (String key : mappingKeys) {
                        String value = mapping.get(key).asText();
                        if (value.startsWith(DEFS_PREFIX)) {
                            String schemaName = sanitizeSchemaName(value.substring(DEFS_PREFIX.length()));
                            mapping.put(key, COMPONENTS_PREFIX + schemaName);
                        }
                    }
                }
            }

            List<String> fields = new ArrayList<>();
            objNode.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                objNode.set(field, fixRefPathsRecursive(objNode.get(field)));
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
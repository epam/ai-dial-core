package com.epam.aidial.core.mcp.schema;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.Settings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.mcp.tools.McpJson;
import com.epam.aidial.core.metaschemas.MetaSchemaHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process schema lookup for {@code dial_describe_schema} (spec 09 §M9). Generates JSON
 * Schemas from {@code :config} POJOs lazily on first use via Victools, returns the DIAL
 * meta-schema for {@code schemas}, and surfaces a structured "not yet implemented" envelope
 * for resource types that have no {@code :config} POJO ({@code files}, {@code prompts},
 * {@code conversations} — deferred to M.1.1).
 *
 * <p>Schema generation is lazy — eager construction on the verticle event loop adds enough
 * latency to the start-up window to race the MCP SDK handshake during integration tests.
 * First-call cost is paid by the caller; the supportedTypes() set is constant.
 */
public class SchemaRegistry {

    private static final Set<String> NOT_IMPLEMENTED_TYPES = Set.of("files", "prompts", "conversations");

    private static final Map<String, Class<?>> POJO_TYPES = Map.of(
            "models", Model.class,
            "applications", Application.class,
            "toolsets", ToolSet.class,
            "interceptors", Interceptor.class,
            "roles", Role.class,
            "keys", Key.class,
            "routes", Route.class,
            "settings", Settings.class);

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "models", "applications", "toolsets", "interceptors", "roles", "keys", "routes", "settings", "schemas");

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private volatile SchemaGenerator generator;

    public String getSchema(String urlSegmentType) {
        if (NOT_IMPLEMENTED_TYPES.contains(urlSegmentType)) {
            ObjectNode envelope = McpJson.MAPPER.createObjectNode();
            envelope.put("error", "Schema for '" + urlSegmentType + "' is not yet available in M.1.0. "
                    + "It will be added in M.1.1.");
            envelope.put("type", urlSegmentType);
            envelope.put("hint", "Use dial_describe_schema with one of: " + String.join(", ", SUPPORTED_TYPES));
            return envelope.toString();
        }
        if (!SUPPORTED_TYPES.contains(urlSegmentType)) {
            throw new IllegalArgumentException("Unknown type '" + urlSegmentType
                    + "'. Call dial_describe_schema with one of: " + String.join(", ", SUPPORTED_TYPES));
        }
        return cache.computeIfAbsent(urlSegmentType, this::generateSchema);
    }

    public Set<String> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    private String generateSchema(String type) {
        if ("schemas".equals(type)) {
            return MetaSchemaHolder.getCustomApplicationMetaSchema();
        }
        JsonNode node = generator().generateSchema(POJO_TYPES.get(type));
        return node.toString();
    }

    private SchemaGenerator generator() {
        SchemaGenerator local = generator;
        if (local == null) {
            synchronized (this) {
                local = generator;
                if (local == null) {
                    SchemaGeneratorConfig config = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                            .with(new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED, JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE))
                            .build();
                    local = new SchemaGenerator(config);
                    generator = local;
                }
            }
        }
        return local;
    }
}

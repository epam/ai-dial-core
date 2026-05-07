package com.epam.aidial.core.mcp.tools;

import com.epam.aidial.core.mcp.schema.SchemaRegistry;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * {@code dial_describe_schema(type)} — registry lookup, no HTTP. Spec 09 §6.1 tool 1, §M9.
 */
public final class DescribeSchemaTool {

    private DescribeSchemaTool() {
    }

    public static McpServerFeatures.AsyncToolSpecification create(SchemaRegistry registry) {
        Map<String, Object> typeProp = Map.of(
                "type", "string",
                "enum", List.copyOf(registry.supportedTypes()),
                "description", "DIAL entity type (URL-segment form). Example: 'models', 'roles', 'settings'.");
        McpSchema.JsonSchema input = new McpSchema.JsonSchema(
                "object",
                Map.of("type", typeProp),
                List.of("type"),
                false, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("dial_describe_schema")
                .description("Returns the JSON Schema for a DIAL entity type. "
                        + "Example: {\"type\":\"models\"} returns the Model schema. "
                        + "Use before constructing a spec for dial_create_resource / dial_update_resource.")
                .inputSchema(input)
                .build();
        return McpServerFeatures.AsyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> handle(registry, request))
                .build();
    }

    private static Mono<McpSchema.CallToolResult> handle(SchemaRegistry registry, McpSchema.CallToolRequest request) {
        Object typeArg = request.arguments() == null ? null : request.arguments().get("type");
        if (!(typeArg instanceof String type) || type.isBlank()) {
            return Mono.just(McpErrors.message("'type' argument is required."));
        }
        try {
            String schema = registry.getSchema(type);
            return Mono.just(McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(schema)))
                    .isError(false)
                    .build());
        } catch (IllegalArgumentException e) {
            return Mono.just(McpErrors.unknownType(type));
        }
    }
}

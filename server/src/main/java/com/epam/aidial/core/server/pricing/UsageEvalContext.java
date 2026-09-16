package com.epam.aidial.core.server.pricing;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.StandardField;
import com.epam.aidial.core.server.util.JsonUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Optional;

/**
 * The usage data a pricing decision tree is evaluated against for one call, resolving requirement
 * 4/5's translation-vs-passthrough priority (a {@code custom_fields.upstream_usage} envelope, when
 * present, is authoritative) and requirement 6's two JSON Path roots ({@code $.usage}/{@code $.upstream_usage}).
 */
public final class UsageEvalContext {

    private final InterfaceType activeInterface;
    private final JsonNode aliasRoot;
    private final JsonNode jsonPathRoot;

    private UsageEvalContext(InterfaceType activeInterface, JsonNode aliasRoot, JsonNode jsonPathRoot) {
        this.activeInterface = activeInterface;
        this.aliasRoot = aliasRoot;
        this.jsonPathRoot = jsonPathRoot;
    }

    public static UsageEvalContext build(InterfaceType nativeInterface, JsonNode nativeRoot) {
        JsonNode upstreamUsage = nativeRoot.path("custom_fields").path("upstream_usage");
        boolean translation = !upstreamUsage.isMissingNode();

        InterfaceType active = translation
                ? tryParseInterface(upstreamUsage.path("interface").asText(null))
                : nativeInterface;
        JsonNode aliasRoot = translation ? upstreamUsage : nativeRoot;

        ObjectNode pathRoot = ProxyUtil.MAPPER.createObjectNode();
        pathRoot.set("usage", nativeRoot.path("usage"));
        pathRoot.set("upstream_usage", upstreamUsage.path("usage"));

        return new UsageEvalContext(active, aliasRoot, pathRoot);
    }

    private static InterfaceType tryParseInterface(String value) {
        if (value == null) {
            return null;
        }
        try {
            return InterfaceType.fromValue(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Optional<JsonNode> resolve(String field) {
        return field.startsWith("$") ? resolveJsonPath(field) : resolveStandardField(field);
    }

    private Optional<JsonNode> resolveStandardField(String field) {
        StandardField standardField = StandardField.fromFieldName(field).orElse(null);
        if (standardField == null) {
            return Optional.empty();
        }
        JsonNode node = StandardFieldResolver.resolve(activeInterface, standardField, aliasRoot);
        return node.isMissingNode() || node.isNull() ? Optional.empty() : Optional.of(node);
    }

    private Optional<JsonNode> resolveJsonPath(String field) {
        JsonNode node = JsonUtil.read(jsonPathRoot, field);
        return node == null || node.isNull() ? Optional.empty() : Optional.of(node);
    }

    public Optional<Long> resolveCounter(StandardField field) {
        JsonNode node = StandardFieldResolver.resolve(activeInterface, field, aliasRoot);
        return node.isNumber() ? Optional.of(node.asLong()) : Optional.empty();
    }
}

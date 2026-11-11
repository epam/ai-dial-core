package com.epam.aidial.core.server.alias;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.server.config.ValidationWarning;
import com.epam.aidial.core.server.service.config.ConfigEntityCodec;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.UrlUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

import static com.epam.aidial.core.server.service.config.ConfigEntityCodec.BLOB_MAPPER;

/**
 * The body of an alias stored under {@code aliases/public/<name>} or {@code aliases/platform/<name>}:
 * {@code presentAs}, {@code target}, {@code userRoles} and the display fields, nothing else. {@code userRoles}
 * decides who may call it, as for an alias of the config file (none means the target's); pricing, features and
 * interfaces come from the target, so it has none of those.
 */
@UtilityClass
public class StoredAliases {

    // the fields a stored alias keeps, by the name it is written under
    private static final List<String> FIELDS = List.of("presentAs", "target", "userRoles", "displayName", "displayVersion",
            "iconUrl", "description", "intro", "descriptionKeywords");

    // the other spellings Alias accepts for the same fields
    private static final Map<String, String> SPELLINGS = Map.of(
            "present_as", "presentAs",
            "user_roles", "userRoles",
            "dial:userRoles", "userRoles",
            "display_name", "displayName",
            "display_version", "displayVersion",
            "icon_url", "iconUrl",
            "description_keywords", "descriptionKeywords");

    private static final String PUBLIC_PREFIX = ResourceDescriptor.PUBLIC_BUCKET + "/";
    private static final String PLATFORM_PREFIX = ResourceDescriptor.PLATFORM_BUCKET + "/";
    private static final Pattern URL_SAFE_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");

    // what a GET answers on top of the fields, so that its body can be sent back as is
    private static final Set<String> IGNORED = Set.of("name", "status");

    /**
     * The alias a {@code PUT} body describes for the alias {@code name}. Throws {@link IllegalArgumentException} for a
     * body that is not an object, whose {@code name} is not {@code name}, that has a field a stored alias does not
     * keep, or whose fields do not parse.
     */
    public Alias fromRequest(JsonNode body, String name) {
        if (!body.isObject()) {
            throw new IllegalArgumentException("Request body must be a JSON object");
        }
        JsonNode bodyName = body.get("name");
        if (bodyName != null && !bodyName.isNull() && !name.equals(bodyName.asText())) {
            throw new IllegalArgumentException("name '%s' is not the name of the alias in the path: %s".formatted(bodyName.asText(), name));
        }
        List<String> unsupported = new ArrayList<>();
        ObjectNode kept = BLOB_MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = body.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = SPELLINGS.getOrDefault(field.getKey(), field.getKey());
            if (FIELDS.contains(fieldName)) {
                kept.set(fieldName, field.getValue());
            } else if (!IGNORED.contains(fieldName)) {
                unsupported.add(field.getKey());
            }
        }
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException("Fields not supported for a stored alias: " + String.join(", ", unsupported)
                    + ". An alias keeps presentAs, target, userRoles and the display fields; the rest comes from its target.");
        }
        return ConfigEntityCodec.treeToEntity(kept, Alias.class);
    }

    /**
     * The stored fields of {@code alias} that are set, in the order of {@link #FIELDS}: the blob body, and what a
     * {@code GET} of the alias answers.
     */
    public ObjectNode toJson(Alias alias) {
        ObjectNode node = BLOB_MAPPER.createObjectNode();
        putIfSet(node, "presentAs", alias.getPresentAs());
        putIfSet(node, "target", alias.getTarget());
        putIfSet(node, "userRoles", alias.getUserRoles());
        putIfSet(node, "displayName", alias.getDisplayName());
        putIfSet(node, "displayVersion", alias.getDisplayVersion());
        putIfSet(node, "iconUrl", alias.getIconUrl());
        putIfSet(node, "description", alias.getDescription());
        putIfSet(node, "intro", alias.getIntro());
        if (alias.getDescriptionKeywords() != null && !alias.getDescriptionKeywords().isEmpty()) {
            node.set("descriptionKeywords", BLOB_MAPPER.valueToTree(alias.getDescriptionKeywords()));
        }
        return node;
    }

    /**
     * A stored blob read back: the alias, or null with the {@code warnings} that make Core ignore it, and the parsed
     * body (null when it is not JSON).
     */
    public record Stored(@Nullable Alias alias, List<ValidationWarning> warnings, @Nullable JsonNode body) {
    }

    /**
     * Reads a stored blob as calls and listings use it: an alias whose fields {@link #validateFields} accepts, or none
     * with the reasons.
     */
    public Stored parse(String body) {
        JsonNode node;
        try {
            node = BLOB_MAPPER.readTree(body);
        } catch (Exception e) {
            return new Stored(null, List.of(new ValidationWarning("body", "not JSON")), null);
        }
        Alias alias;
        try {
            alias = BLOB_MAPPER.treeToValue(node, Alias.class);
        } catch (Exception e) {
            return new Stored(null, List.of(new ValidationWarning("body", e.getMessage())), node);
        }
        List<ValidationWarning> warnings = validateFields(alias);
        return new Stored(warnings.isEmpty() ? alias : null, warnings, node);
    }

    /**
     * {@link AliasTargets#validateFields}, and a target that is a config deployment or a resource in the public or
     * platform bucket. A stored alias is open to the callers of its {@code userRoles}, so it must not point to a
     * resource in a user's bucket, whose owner could change it at any time.
     */
    public List<ValidationWarning> validateFields(Alias alias) {
        List<ValidationWarning> warnings = AliasTargets.validateFields(alias);
        if (warnings.isEmpty()) {
            String path = alias.getTarget().getPath();
            if (AliasTargets.isResourcePath(path)) {
                String typePrefix = path.substring(0, path.indexOf('/') + 1);
                if (!path.startsWith(typePrefix + PUBLIC_PREFIX) && !path.startsWith(typePrefix + PLATFORM_PREFIX)) {
                    warnings.add(new ValidationWarning("target.path",
                            "The target of a stored alias must be a config deployment or a resource in public or platform storage: " + path));
                }
            }
        }
        return warnings;
    }

    /**
     * Whether {@code name} is a stored alias name its url carries unchanged, so that the id Core reports
     * ({@code aliases/public/<name>}) is the id a caller sends back, in a path or in a body {@code model}: letters,
     * digits, {@code .}, {@code _} and {@code -}. A character the path encoding changes ({@code %}, {@code [}, a space)
     * or one a path can not carry as is ({@code :}) is refused.
     */
    public boolean isUrlSafeName(String name) {
        return URL_SAFE_NAME.matcher(name).matches() && name.equals(UrlUtil.encodePathSegment(name));
    }

    private static void putIfSet(ObjectNode node, String field, Object value) {
        if (value != null) {
            node.set(field, BLOB_MAPPER.valueToTree(value));
        }
    }
}

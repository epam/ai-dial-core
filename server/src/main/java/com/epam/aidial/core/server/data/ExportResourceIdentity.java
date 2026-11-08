package com.epam.aidial.core.server.data;

import com.epam.aidial.core.server.util.HashUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The identity of a resource in an export: type, origin, decrypted bucket location and path, and a schema's body
 * {@code $id}. The type is spelled with one name per kind, so the URL segment and the blob group of a
 * schema are not both in use. A location other than {@code public/} and {@code platform/} is reported as origin
 * {@code other} without its bucket location, because such a target is never read and its location is not for the export
 * to disclose.
 *
 * @param bucketLocation {@code public/} or {@code platform/}; absent for a file entity and for another location
 * @param schemaId       the {@code $id} of a schema
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportResourceIdentity(String type, String origin, @Nullable String bucketLocation, String path,
                                     @Nullable String schemaId) {

    /** The bucket location of the descriptor of an entity that the file configuration defines; it has no blob. */
    public static final String FILE_LOCATION = "file/";

    /** The directory of the fragments of the file configuration in an archive. */
    public static final String FRAGMENT_PREFIX = ".dial-export/config/";

    private static final Map<ResourceTypes, String> TYPE_NAMES = Map.of(
            ResourceTypes.APPLICATION, "application",
            ResourceTypes.TOOL_SET, "toolset",
            ResourceTypes.MODEL, "model",
            ResourceTypes.INTERCEPTOR, "interceptor",
            ResourceTypes.APP_TYPE_SCHEMA, "application-type-schema",
            ResourceTypes.CATALOG_SCHEMA, "catalog-schema");

    public static ExportResourceIdentity ofDescriptor(ResourceDescriptor descriptor, @Nullable String schemaId) {
        String location = descriptor.getBucketLocation();
        if (FILE_LOCATION.equals(location)) {
            return ofFileEntity((ResourceTypes) descriptor.getType(), descriptor.getName(), schemaId);
        }
        boolean isPlatform = ResourceDescriptor.PLATFORM_LOCATION.equals(location);
        boolean isPublic = ResourceDescriptor.PUBLIC_LOCATION.equals(location);
        String relativePath = descriptor.getParentPath() == null ? descriptor.getName()
                : descriptor.getParentPath() + ResourceDescriptor.PATH_SEPARATOR + descriptor.getName();
        return new ExportResourceIdentity(typeName((ResourceTypes) descriptor.getType()),
                isPlatform ? "platform" : isPublic ? "public" : "other", isPlatform || isPublic ? location : null,
                relativePath, schemaId);
    }

    public static ExportResourceIdentity ofFileEntity(ResourceTypes type, String name, @Nullable String schemaId) {
        return new ExportResourceIdentity(typeName(type), "file", null, name, schemaId);
    }

    /**
     * The path of the fragment of a resource of the file configuration, {@code .dial-export/config/<type>/<segment>.json}. It has no
     * {@code :} and no {@code %}: a path segment keeps {@code A-Z a-z 0-9 . _ -} and writes every other byte as {@code ~} and two hex
     * digits, which is unambiguous because {@code ~} is written that way too; a schema is named by a digest of its {@code $id}. A
     * resource that has a blob is named by its storage path instead, which the descriptor gives.
     *
     * @throws IllegalStateException if a segment of the path is empty, {@code .} or {@code ..}, which no archive entry may hold
     */
    @JsonIgnore
    public String fragmentName() {
        return FRAGMENT_PREFIX + type + "/" + segment() + ".json";
    }

    /**
     * The RFC 6901 pointer to the entity of a resource of the file configuration, for example {@code /models/<name>}. For a schema the
     * last segment is the digest of its {@code $id}, the segment of its key, so the {@code $id} is not disclosed.
     *
     * @return the pointer, or {@code null} for a resource that is not of origin {@code file} or whose type the file configuration does
     *         not hold
     */
    @JsonIgnore
    @Nullable
    public String sourcePointer() {
        if (!"file".equals(origin)) {
            return null;
        }
        String section = switch (type) {
            case "application" -> "applications";
            case "toolset" -> "toolsets";
            case "model" -> "models";
            case "interceptor" -> "interceptors";
            case "application-type-schema" -> "applicationTypeSchemas";
            case "catalog-schema" -> "catalogSchemas";
            default -> null;
        };
        return section == null ? null : "/" + section + "/" + path.replace("~", "~0").replace("/", "~1");
    }

    /**
     * The key of this resource in the manifest: {@code <type>:<origin>:<segment>}, where the segment is the escaped path, or the
     * digest of the {@code $id} for a schema. A type and an origin never hold {@code :}, and the escaping writes every {@code :} of
     * a path as {@code ~3A}, so the key is split unambiguously at its first two colons and two identities never share a key. The
     * bucket location is not part of it: for every resource that an export can hold it follows from the origin. A reader treats
     * the key as opaque.
     *
     * @throws IllegalStateException as {@link #fragmentName()}
     */
    @JsonIgnore
    public String key() {
        return type + ":" + origin + ":" + segment();
    }

    /**
     * The key of a file of a Skill in the registry: {@code skill-file:<origin>:<segment of the Skill>/<escaped relative path>}. The type
     * {@code skill-file} is not a type of resource, so the key cannot collide with the key of a resource.
     *
     * @throws IllegalStateException if a segment of the Skill's path or of the relative path is empty, {@code .} or {@code ..}
     */
    public String skillFileKey(String relativePath) {
        return "skill-file:" + origin + ":" + segment() + "/" + escapePath(requireSafeSegments(relativePath));
    }

    /**
     * @throws IllegalStateException if a segment of the relative path is empty, {@code .} or {@code ..}, which no archive entry may hold
     */
    public static String requireSafeRelativePath(String relativePath) {
        return requireSafeSegments(relativePath);
    }

    private String segment() {
        return schemaId != null ? schemaDigest(schemaId) : escapePath(requireSafeSegments(path));
    }

    /**
     * @return the name that identities use for a resource type
     */
    public static String typeName(ResourceTypes type) {
        return TYPE_NAMES.getOrDefault(type, type.urlSegment());
    }

    /**
     * @return the spelling of a status or a kind on the wire: the constant in lower case with hyphens, for example
     *         {@code unresolved-short-name}
     */
    public static String wireName(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String requireSafeSegments(String path) {
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                // no path in the message: it belongs to a resource that is not exported
                throw new IllegalStateException("A resource path has a segment that cannot be part of an archive entry name");
            }
        }
        return path;
    }

    private static String escapePath(String path) {
        StringBuilder escaped = new StringBuilder();
        for (byte value : path.getBytes(StandardCharsets.UTF_8)) {
            char character = (char) (value & 0xFF);
            boolean isSafe = character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9' || character == '.' || character == '_' || character == '-'
                    || character == '/';
            if (isSafe) {
                escaped.append(character);
            } else {
                escaped.append('~').append(String.format("%02X", value & 0xFF));
            }
        }
        return escaped.toString();
    }

    /**
     * @return the segment of the key and the entry name of a schema: a digest of its {@code $id}
     */
    public static String schemaDigest(String schemaId) {
        // the first 16 bytes of the digest, as hex
        return HashUtil.sha256Hex(schemaId).substring(0, 32);
    }
}

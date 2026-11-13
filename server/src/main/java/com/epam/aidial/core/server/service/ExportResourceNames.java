package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * The names that an export gives a resource: its key for the log and the messages, {@code <type>:<origin>:<segment>}, and the entry name
 * of an entity of the file configuration. A location other than {@code public/} and {@code platform/} is reported as origin
 * {@code other}, because such a location is not for the export to disclose.
 */
final class ExportResourceNames {

    private static final String FRAGMENT_PREFIX = ".dial-export/config/";

    private static final Map<ResourceTypes, String> TYPE_NAMES = Map.of(
            ResourceTypes.APPLICATION, "application",
            ResourceTypes.TOOL_SET, "toolset",
            ResourceTypes.MODEL, "model",
            ResourceTypes.INTERCEPTOR, "interceptor",
            ResourceTypes.APP_TYPE_SCHEMA, "application-type-schema",
            ResourceTypes.CATALOG_SCHEMA, "catalog-schema");

    private ExportResourceNames() {
    }

    /**
     * The key of a resource; the escaped path is the segment. Every byte but {@code A-Z a-z 0-9 . _ -} is written as {@code ~} and two hex
     * digits, {@code :} included, so two resources never share a key.
     */
    static String buildKey(ResourceDescriptor descriptor) {
        return resolveTypeName(ExportTypePolicy.resolveType(descriptor)) + ":" + resolveOrigin(descriptor) + ":"
                + escapePath(resolvePath(descriptor));
    }

    /**
     * @return {@code .dial-export/config/<type>/<segment>.json}, for a resource of the file configuration
     */
    static String buildFragmentName(ResourceDescriptor descriptor) {
        return FRAGMENT_PREFIX + resolveTypeName(ExportTypePolicy.resolveType(descriptor)) + "/" + escapePath(resolvePath(descriptor)) + ".json";
    }

    /**
     * @throws IllegalStateException if a segment of the path is empty, {@code .} or {@code ..}, which no archive entry may hold; the message
     *         names the key
     */
    static void requireSafePath(ResourceDescriptor descriptor) {
        if (hasUnsafeSegment(resolvePath(descriptor))) {
            throw newUnsafePathException(buildKey(descriptor));
        }
    }

    /**
     * @param resourceKey the key of the resource that holds the path, for the message
     * @throws IllegalStateException as {@link #requireSafePath}
     */
    static void requireSafeRelativePath(String relativePath, String resourceKey) {
        if (hasUnsafeSegment(relativePath)) {
            throw newUnsafePathException(resourceKey);
        }
    }

    static String resolveTypeName(ResourceTypes type) {
        return TYPE_NAMES.getOrDefault(type, type.urlSegment());
    }

    /**
     * @return the constant in lower case with hyphens, for example {@code unresolved-short-name}
     */
    static String toWireName(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String resolveOrigin(ResourceDescriptor descriptor) {
        if (ExportFileEntities.isFile(descriptor)) {
            return "file";
        }
        String location = descriptor.getBucketLocation();
        if (ResourceDescriptor.PLATFORM_LOCATION.equals(location)) {
            return "platform";
        }
        return ResourceDescriptor.PUBLIC_LOCATION.equals(location) ? "public" : "other";
    }

    private static String resolvePath(ResourceDescriptor descriptor) {
        if (ExportFileEntities.isFile(descriptor) || descriptor.getParentPath() == null) {
            return descriptor.getName();
        }
        return descriptor.getParentPath() + ResourceDescriptor.PATH_SEPARATOR + descriptor.getName();
    }

    private static boolean hasUnsafeSegment(String path) {
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static IllegalStateException newUnsafePathException(String resourceKey) {
        return new IllegalStateException("A resource path has a segment that cannot be part of an archive entry name (resource="
                + resourceKey + ")");
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
}

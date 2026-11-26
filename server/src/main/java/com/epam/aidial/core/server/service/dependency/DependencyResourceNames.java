package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * The names that the dependency list gives a resource: its key for the log, {@code <type>:<origin>:<segment>}, the name of its type and its
 * origin. A location other than {@code public/}, {@code platform/} and the file configuration is a user bucket and is reported as origin
 * {@code private}, because the list never discloses the location of a user.
 */
final class DependencyResourceNames {

    static final String PUBLIC = "public";
    static final String PLATFORM = "platform";
    static final String FILE = "file";
    static final String PRIVATE = "private";

    private static final Map<ResourceTypes, String> TYPE_NAMES = Map.of(
            ResourceTypes.APPLICATION, "application",
            ResourceTypes.MODEL, "model",
            ResourceTypes.INTERCEPTOR, "interceptor",
            ResourceTypes.APP_TYPE_SCHEMA, "application-type-schema",
            ResourceTypes.CATALOG_SCHEMA, "catalog-schema",
            ResourceTypes.FILE, "file",
            ResourceTypes.PROMPT, "prompt",
            ResourceTypes.SKILL, "skill");

    private DependencyResourceNames() {
    }

    /**
     * The key of a resource; the escaped path is the segment. Every byte but {@code A-Z a-z 0-9 . _ - /} is written as {@code ~} and two hex
     * digits, {@code :} included, so two resources never share a key.
     */
    static String buildKey(ResourceDescriptor descriptor) {
        return resolveTypeName(DependencyTypePolicy.resolveType(descriptor)) + ":" + resolveOrigin(descriptor) + ":"
                + escapePath(resolvePath(descriptor));
    }

    static String resolveTypeName(ResourceTypes type) {
        return TYPE_NAMES.getOrDefault(type, type.urlSegment());
    }

    /**
     * @return {@code file}, {@code platform}, {@code public}, or {@code private} for a user bucket
     */
    static String resolveOrigin(ResourceDescriptor descriptor) {
        if (FileConfigEntities.isFile(descriptor)) {
            return FILE;
        }
        String location = descriptor.getBucketLocation();
        if (ResourceDescriptor.PLATFORM_LOCATION.equals(location)) {
            return PLATFORM;
        }
        return ResourceDescriptor.PUBLIC_LOCATION.equals(location) ? PUBLIC : PRIVATE;
    }

    /**
     * @return the constant in lower case with hyphens, for example {@code unresolved-short-name}
     */
    static String toWireName(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String resolvePath(ResourceDescriptor descriptor) {
        if (FileConfigEntities.isFile(descriptor) || descriptor.getParentPath() == null) {
            return descriptor.getName();
        }
        return descriptor.getParentPath() + ResourceDescriptor.PATH_SEPARATOR + descriptor.getName();
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

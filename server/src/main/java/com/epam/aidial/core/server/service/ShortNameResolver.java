package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ShortNameResolution.Origin;
import com.epam.aidial.core.server.service.ShortNameResolution.Outcome;
import com.epam.aidial.core.server.service.ShortNameResolution.Skipped;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.UrlUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Resolves a short name to the effective entity and says where it came from, with read-only signals: the merged configuration,
 * the file configuration, the platform blob and the skipped entities. It reads one snapshot that the caller passes in, so one
 * instance serves a whole export.
 */
public final class ShortNameResolver {

    public enum ReferenceForm {
        EXTERNAL_URL, RESOURCE_URL, SHORT_NAME, MALFORMED
    }

    /**
     * @param descriptor set only for {@link ReferenceForm#RESOURCE_URL}
     */
    public record ClassifiedReference(ReferenceForm form, @Nullable ResourceDescriptor descriptor) {
    }

    private static final List<ResourceTypes> DEPLOYMENT_TYPES =
            List.of(ResourceTypes.APPLICATION, ResourceTypes.MODEL, ResourceTypes.TOOL_SET, ResourceTypes.INTERCEPTOR);

    private final Config merged;
    private final Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalidEntities;
    @Nullable
    private final Config fileConfig;
    private final ResourceService resourceService;

    /**
     * @param fileConfig the file-sourced {@code Config}, or {@code null} when the file store has none; without it no
     *                   origin can be established
     */
    public ShortNameResolver(Config merged, Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalidEntities,
                             @Nullable Config fileConfig, ResourceService resourceService) {
        this.merged = merged;
        this.invalidEntities = invalidEntities;
        this.fileConfig = fileConfig;
        this.resourceService = resourceService;
    }

    /**
     * Classifies a marked value: an absolute URL is external, a value that parses as a resource URL carries its
     * descriptor, another slash-free value is a short name, and anything else, including a blank value, is malformed.
     */
    public static ClassifiedReference classify(@Nullable String value, EncryptionService encryptionService) {
        if (value == null || value.isBlank()) {
            return new ClassifiedReference(ReferenceForm.MALFORMED, null);
        }
        if (UrlUtil.isAbsoluteUrl(value)) {
            return new ClassifiedReference(ReferenceForm.EXTERNAL_URL, null);
        }
        try {
            if (hasUnsafeSegment(value)) {
                return new ClassifiedReference(ReferenceForm.MALFORMED, null);
            }
            return new ClassifiedReference(ReferenceForm.RESOURCE_URL, ResourceDescriptorFactory.fromAnyUrl(value, encryptionService));
        } catch (RuntimeException e) {
            // not a resource URL; UrlUtil.decodePath rethrows URISyntaxException as RuntimeException
            return new ClassifiedReference(value.indexOf('/') < 0 ? ReferenceForm.SHORT_NAME : ReferenceForm.MALFORMED, null);
        }
    }

    /**
     * A path segment that is empty, {@code .} or {@code ..}, in the raw value or after it is decoded (so also {@code %2e%2e}, and a
     * segment that decodes to something that holds one), is never a resource: it is refused before anything is read or an entry name
     * is built. Only a value with a slash is examined, and a single trailing slash, which names a folder, is allowed. A value that cannot
     * be decoded is left to the parser, which refuses it.
     */
    private static boolean hasUnsafeSegment(String value) {
        if (value.indexOf('/') < 0) {
            return false;
        }
        String[] segments = value.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            boolean isTrailing = i == segments.length - 1 && i > 0;
            if (segments[i].isEmpty()) {
                if (isTrailing) {
                    continue;
                }
                return true;
            }
            String decoded;
            try {
                decoded = UrlUtil.decodePath(segments[i]);
            } catch (RuntimeException e) {
                return false;
            }
            for (String part : decoded.split("[/\\\\]", -1)) {
                if (part.equals(".") || part.equals("..")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @param typeHint {@link ResourceTypes#INTERCEPTOR} to look only among interceptors, otherwise {@code null}
     * @param name     a slash-free short name
     */
    public ShortNameResolution resolve(@Nullable ResourceTypes typeHint, String name) {
        if (typeHint != null && typeHint != ResourceTypes.INTERCEPTOR) {
            throw new IllegalArgumentException("Unsupported type hint: " + typeHint);
        }
        if (name.isEmpty() || name.indexOf('/') >= 0) {
            throw new IllegalArgumentException("Not a short name");
        }
        List<ResourceTypes> candidates = typeHint == null ? DEPLOYMENT_TYPES : List.of(typeHint);
        List<ResourceTypes> holders = candidates.stream().filter(type -> findEntity(merged, type, name) != null).toList();
        List<Skipped> skipped = findSkipped(candidates, name);

        if (holders.size() > 1) {
            return result(Outcome.ORIGIN_UNKNOWN, null, name, null, null, false, skipped);
        }
        if (holders.isEmpty()) {
            return resolveAbsent(candidates, name, skipped);
        }
        return resolveHeld(holders.get(0), name, skipped);
    }

    private ShortNameResolution resolveHeld(ResourceTypes type, String name, List<Skipped> skipped) {
        if (fileConfig == null) {
            return result(Outcome.ORIGIN_UNKNOWN, type, name, null, null, false, skipped);
        }
        Object fileEntity = findEntity(fileConfig, type, name);
        boolean isFileInstance = fileEntity != null && fileEntity == findEntity(merged, type, name);
        boolean hasBlob = hasPlatformBlob(type, name);
        boolean hasInvalidBlob = skipped.stream().anyMatch(entry -> entry.type() == type);

        if (isFileInstance) {
            return hasBlob && !hasInvalidBlob
                    ? result(Outcome.ORIGIN_UNKNOWN, type, name, null, null, false, skipped)
                    : result(Outcome.FOUND, type, name, null, Origin.FILE, false, skipped);
        }
        if (!hasBlob) {
            return result(Outcome.ORIGIN_UNKNOWN, type, name, null, null, false, skipped);
        }
        // the export takes the file entity when a file entity and a platform blob share the type and name; the blob is shadowed and
        // the manifest says so (the merged configuration serves the blob, which is not what the export is about)
        return fileEntity != null
                ? result(Outcome.FOUND, type, name, null, Origin.FILE, true, skipped)
                : result(Outcome.FOUND, type, name, canonicalId(type, name), Origin.PLATFORM, false, skipped);
    }

    private ShortNameResolution resolveAbsent(List<ResourceTypes> candidates, String name, List<Skipped> skipped) {
        if (!skipped.isEmpty()) {
            return result(Outcome.SKIPPED, null, name, null, null, false, skipped);
        }
        boolean hasBlob = candidates.stream().anyMatch(type -> hasPlatformBlob(type, name));
        return result(hasBlob ? Outcome.ORIGIN_UNKNOWN : Outcome.NOT_FOUND, null, name, null, null, false, skipped);
    }

    private List<Skipped> findSkipped(List<ResourceTypes> candidates, String name) {
        List<Skipped> skipped = new ArrayList<>();
        for (ResourceTypes type : candidates) {
            InvalidEntityRecord entry = invalidEntities.getOrDefault(type, Map.of()).get(canonicalId(type, name));
            if (entry != null) {
                skipped.add(new Skipped(type, entry.getCanonicalId(), entry.getSource(), entry.getReason()));
            }
        }
        return skipped;
    }

    private boolean hasPlatformBlob(ResourceTypes type, String name) {
        if (!ExportTypePolicy.isBound(type, ResourceDescriptor.PLATFORM_BUCKET)) {
            return false; // entities of this type are never stored in the platform bucket, so there is nothing to probe
        }
        return resourceService.hasResource(ResourceDescriptorFactory.fromDecoded(
                type, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, name));
    }

    private static String canonicalId(ResourceTypes type, String name) {
        return MergedConfigStore.canonicalId(type, ResourceDescriptor.PLATFORM_BUCKET, name);
    }

    @Nullable
    private static Object findEntity(Config config, ResourceTypes type, String name) {
        return switch (type) {
            case APPLICATION -> config.getApplications().get(name);
            case MODEL -> config.getModels().get(name);
            case TOOL_SET -> config.getToolsets().get(name);
            default -> config.getInterceptors().get(name);
        };
    }

    private static ShortNameResolution result(Outcome outcome, @Nullable ResourceTypes type, String name, @Nullable String canonicalId,
                                              @Nullable Origin origin, boolean hasShadowedEntity, List<Skipped> skipped) {
        return new ShortNameResolution(outcome, type, name, canonicalId, origin, hasShadowedEntity, skipped);
    }
}

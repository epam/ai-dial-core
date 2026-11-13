package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ShortNameResolution.Origin;
import com.epam.aidial.core.server.service.ShortNameResolution.Outcome;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.UrlUtil;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Resolves a short name to the effective entity and says where it came from, from read-only signals: the merged configuration, the file
 * configuration, the platform blob and the skipped entities. It reads one snapshot that the caller passes in, so one instance serves a
 * whole export.
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

    private static final List<ResourceTypes> DEPLOYMENT_TYPES = ExportTypePolicy.listExaminedTypes();

    private final Config merged;
    private final Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalidEntities;
    @Nullable
    private final Config fileConfig;
    private final ResourceService resourceService;

    /**
     * @param fileConfig {@code null} when the file store has none; without it no origin can be established
     */
    public ShortNameResolver(Config merged, Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalidEntities,
                             @Nullable Config fileConfig, ResourceService resourceService) {
        this.merged = merged;
        this.invalidEntities = invalidEntities;
        this.fileConfig = fileConfig;
        this.resourceService = resourceService;
    }

    /**
     * Classifies a marked value: an absolute URL is external, a resource URL carries its descriptor, another slash-free value is a short
     * name, and anything else, including a blank value, is malformed.
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
     * A path segment that is empty, {@code .} or {@code ..}, raw or decoded (so also {@code %2e%2e}), is never a resource: it is refused
     * before anything is read or an entry name is built. Only a value with a slash is examined, and a single trailing slash, which names a
     * folder, is allowed. A value that cannot be decoded is left to the parser, which refuses it.
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
     */
    public ShortNameResolution resolve(@Nullable ResourceTypes typeHint, String name) {
        if (typeHint != null && typeHint != ResourceTypes.INTERCEPTOR) {
            throw new IllegalArgumentException("Unsupported type hint: " + typeHint);
        }
        if (name.isEmpty() || name.indexOf('/') >= 0) {
            throw new IllegalArgumentException("Not a short name");
        }
        List<ResourceTypes> candidates = typeHint == null ? DEPLOYMENT_TYPES : List.of(typeHint);
        List<ResourceTypes> holders = candidates.stream().filter(type -> ExportTypePolicy.findDeployment(merged, type, name) != null).toList();
        Set<ResourceTypes> skipped = findSkipped(candidates, name);

        if (holders.size() > 1) {
            return newResolution(Outcome.ORIGIN_UNKNOWN, null, name, null);
        }
        if (holders.isEmpty()) {
            return resolveAbsent(candidates, name, skipped);
        }
        return resolveHeld(holders.get(0), name, skipped);
    }

    /**
     * A valid platform blob wins over a file entity of the same type and name, whichever entity the merged map holds; the file
     * configuration's instance is the origin only when no valid blob exists. Anything else leaves the origin unknown.
     */
    private ShortNameResolution resolveHeld(ResourceTypes type, String name, Set<ResourceTypes> skipped) {
        if (fileConfig == null) {
            return newResolution(Outcome.ORIGIN_UNKNOWN, type, name, null);
        }
        Deployment fileEntity = ExportTypePolicy.findDeployment(fileConfig, type, name);
        boolean isFileInstance = fileEntity != null && fileEntity == ExportTypePolicy.findDeployment(merged, type, name);
        boolean hasValidBlob = hasPlatformBlob(type, name) && !skipped.contains(type);

        if (hasValidBlob) {
            return newResolution(Outcome.FOUND, type, name, Origin.PLATFORM);
        }
        if (isFileInstance) {
            return newResolution(Outcome.FOUND, type, name, Origin.FILE);
        }
        return newResolution(Outcome.ORIGIN_UNKNOWN, type, name, null);
    }

    private ShortNameResolution resolveAbsent(List<ResourceTypes> candidates, String name, Set<ResourceTypes> skipped) {
        if (!skipped.isEmpty()) {
            return newResolution(Outcome.SKIPPED, null, name, null);
        }
        boolean hasBlob = candidates.stream().anyMatch(type -> hasPlatformBlob(type, name));
        return newResolution(hasBlob ? Outcome.ORIGIN_UNKNOWN : Outcome.NOT_FOUND, null, name, null);
    }

    private Set<ResourceTypes> findSkipped(List<ResourceTypes> candidates, String name) {
        Set<ResourceTypes> skipped = EnumSet.noneOf(ResourceTypes.class);
        for (ResourceTypes type : candidates) {
            if (invalidEntities.getOrDefault(type, Map.of()).containsKey(buildPlatformCanonicalId(type, name))) {
                skipped.add(type);
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

    private static String buildPlatformCanonicalId(ResourceTypes type, String name) {
        return MergedConfigStore.canonicalId(type, ResourceDescriptor.PLATFORM_BUCKET, name);
    }

    private static ShortNameResolution newResolution(Outcome outcome, @Nullable ResourceTypes type, String name, @Nullable Origin origin) {
        return new ShortNameResolution(outcome, type, name, origin);
    }
}

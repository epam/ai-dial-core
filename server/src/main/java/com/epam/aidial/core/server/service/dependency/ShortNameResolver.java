package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.service.dependency.ShortNameResolution.Origin;
import com.epam.aidial.core.server.service.dependency.ShortNameResolution.Outcome;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Resolves a short name to the effective entity and says where it came from, from read-only signals: the merged configuration, the file
 * configuration, the platform blob and the skipped entities. It reads one snapshot that the caller passes in, so one instance serves a
 * whole walk.
 */
final class ShortNameResolver {

    private static final List<ResourceTypes> DEPLOYMENT_TYPES = DependencyTypePolicy.listExaminedTypes();
    private static final List<ResourceTypes> INTERCEPTOR_TYPES = List.of(ResourceTypes.INTERCEPTOR);

    private final Config merged;
    private final Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalidEntities;
    @Nullable
    private final Config fileConfig;
    private final ResourceService resourceService;

    ShortNameResolver(ConfigSnapshot snapshot, ResourceService resourceService) {
        this.merged = snapshot.merged();
        this.invalidEntities = snapshot.invalidEntities();
        this.fileConfig = snapshot.file();
        this.resourceService = resourceService;
    }

    /**
     * Looks the name up among every type that the walk examines.
     */
    ShortNameResolution resolve(String name) {
        return resolveAmong(DEPLOYMENT_TYPES, name);
    }

    /**
     * Looks the name up among the interceptors only.
     */
    ShortNameResolution resolveInterceptor(String name) {
        return resolveAmong(INTERCEPTOR_TYPES, name);
    }

    private ShortNameResolution resolveAmong(List<ResourceTypes> candidates, String name) {
        if (name.isEmpty() || name.indexOf('/') >= 0) {
            throw new IllegalArgumentException("Not a short name");
        }
        List<ResourceTypes> holders = candidates.stream().filter(type -> DependencyTypePolicy.findDeployment(merged, type, name) != null).toList();
        Set<ResourceTypes> skipped = findSkipped(candidates, name);

        if (holders.size() > 1) {
            return new ShortNameResolution(Outcome.ORIGIN_UNKNOWN, null, name, null);
        }
        if (holders.isEmpty()) {
            return resolveAbsent(candidates, name, skipped);
        }
        return resolveHeld(holders.get(0), name, skipped);
    }

    /**
     * A valid platform blob wins over a file entity of the same type and name, whichever entity the merged map holds; the file
     * configuration's entity is the origin only when no valid blob exists and the merged map holds an equal one. Anything else leaves the origin unknown.
     */
    private ShortNameResolution resolveHeld(ResourceTypes type, String name, Set<ResourceTypes> skipped) {
        if (fileConfig == null) {
            return new ShortNameResolution(Outcome.ORIGIN_UNKNOWN, type, name, null);
        }
        Deployment fileEntity = DependencyTypePolicy.findDeployment(fileConfig, type, name);
        boolean isFileEntity = fileEntity != null && fileEntity.equals(DependencyTypePolicy.findDeployment(merged, type, name));
        boolean hasValidBlob = hasPlatformBlob(type, name) && !skipped.contains(type);

        if (hasValidBlob) {
            return new ShortNameResolution(Outcome.FOUND, type, name, Origin.PLATFORM);
        }
        if (isFileEntity) {
            return new ShortNameResolution(Outcome.FOUND, type, name, Origin.FILE);
        }
        return new ShortNameResolution(Outcome.ORIGIN_UNKNOWN, type, name, null);
    }

    private ShortNameResolution resolveAbsent(List<ResourceTypes> candidates, String name, Set<ResourceTypes> skipped) {
        if (!skipped.isEmpty()) {
            return new ShortNameResolution(Outcome.SKIPPED, null, name, null);
        }
        boolean hasBlob = candidates.stream().anyMatch(type -> hasPlatformBlob(type, name));
        return new ShortNameResolution(hasBlob ? Outcome.ORIGIN_UNKNOWN : Outcome.NOT_FOUND, null, name, null);
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
        if (!DependencyTypePolicy.isBound(type, ResourceDescriptor.PLATFORM_BUCKET)) {
            return false; // entities of this type are never stored in the platform bucket, so there is nothing to probe
        }
        return resourceService.hasResource(ResourceDescriptorFactory.fromDecoded(
                type, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, name));
    }

    private static String buildPlatformCanonicalId(ResourceTypes type, String name) {
        return MergedConfigStore.canonicalId(type, ResourceDescriptor.PLATFORM_BUCKET, name);
    }
}


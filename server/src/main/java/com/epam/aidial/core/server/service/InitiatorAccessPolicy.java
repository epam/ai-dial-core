package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * The policy of a caller who is not an explicit admin. Stored resources (public, own and shared buckets) are listed by the right to read on the
 * caller's own standing; a Model or an Application of the configuration is listed by its roles and has no URL; an Interceptor and a schema of
 * the configuration are listed.
 */
public final class InitiatorAccessPolicy implements DependencyAccessPolicy {

    private static final String USER_BUCKET_PREFIX = "Users/";
    private static final String API_KEY_BUCKET_PREFIX = "Keys/";
    /** The types that a user bucket holds and that the walk follows: the others are configuration entities. */
    private static final Set<ResourceTypes> USER_BUCKET_TYPES = Set.of(ResourceTypes.APPLICATION, ResourceTypes.FILE, ResourceTypes.PROMPT,
            ResourceTypes.SKILL);
    /** The types that have roles of their own: a short name or a platform URL resolves to them whatever the roles are. */
    private static final Set<ResourceTypes> ROLE_CHECKED_TYPES = Set.of(ResourceTypes.MODEL, ResourceTypes.APPLICATION);

    private final Function<Set<ResourceDescriptor>, Map<ResourceDescriptor, Set<ResourceAccessType>>> initiatorPermissions;
    private final Supplier<Config> mergedConfigSource;
    private final Supplier<Config> fileConfigSource;
    private final List<String> userRoles;

    public InitiatorAccessPolicy(Function<Set<ResourceDescriptor>, Map<ResourceDescriptor, Set<ResourceAccessType>>> initiatorPermissions,
                                 Supplier<Config> mergedConfigSource, Supplier<Config> fileConfigSource, @Nullable List<String> userRoles) {
        this.initiatorPermissions = initiatorPermissions;
        this.mergedConfigSource = mergedConfigSource;
        this.fileConfigSource = fileConfigSource;
        this.userRoles = userRoles == null ? List.of() : userRoles;
    }

    @Override
    public boolean isRootAllowed(ResourceDescriptor root) {
        ResourceTypes type = DependencyTypePolicy.resolveType(root);
        return !root.isFolder() && DependencyTypePolicy.isRoot(type) && isStored(root) && isSupported(root) && selectReadable(Set.of(root)).contains(root);
    }

    @Override
    public boolean isSupported(ResourceDescriptor target) {
        if (target.isFolder()) {
            return false;
        }
        ResourceTypes type = DependencyTypePolicy.resolveType(target);
        if (DependencyTypePolicy.resolveContent(type) == null) {
            return false;
        }
        if (FileConfigEntities.isFile(target)) {
            return true;
        }
        String location = target.getBucketLocation();
        if (ResourceDescriptor.PLATFORM_LOCATION.equals(location)) {
            return DependencyTypePolicy.isBound(type, ResourceDescriptor.PLATFORM_BUCKET);
        }
        if (ResourceDescriptor.PUBLIC_LOCATION.equals(location)) {
            return DependencyTypePolicy.isBound(type, ResourceDescriptor.PUBLIC_BUCKET);
        }
        return isUserBucket(location) && USER_BUCKET_TYPES.contains(type);
    }

    @Override
    public Set<ResourceDescriptor> selectListable(Set<ResourceDescriptor> targets) {
        Set<ResourceDescriptor> byRight = new LinkedHashSet<>();
        Set<ResourceDescriptor> byConfiguration = new LinkedHashSet<>();
        for (ResourceDescriptor target : targets) {
            if (!isSupported(target)) {
                continue;
            }
            (isConfiguration(target) ? byConfiguration : byRight).add(target);
        }
        Set<ResourceDescriptor> allowed = new LinkedHashSet<>();
        if (!byRight.isEmpty()) {
            allowed.addAll(selectReadable(byRight));
        }
        for (ResourceDescriptor target : byConfiguration) {
            if (isAdmittedConfigurationEntity(target)) {
                allowed.add(target);
            }
        }
        return allowed;
    }

    /** An entity of the file configuration has no URL, and neither has a Model or an Application of the configuration that the caller sees. */
    @Override
    public boolean isUrlExposed(ResourceDescriptor target) {
        return !FileConfigEntities.isFile(target) && !(isConfiguration(target) && ROLE_CHECKED_TYPES.contains(DependencyTypePolicy.resolveType(target)));
    }

    /** A resource in {@code public/} or in a user bucket: the right to read decides. */
    private static boolean isStored(ResourceDescriptor target) {
        String location = target.getBucketLocation();
        return !FileConfigEntities.isFile(target) && (ResourceDescriptor.PUBLIC_LOCATION.equals(location) || isUserBucket(location));
    }

    /** An entity of the file configuration or of {@code platform/}: the roles decide, where it has any. */
    private static boolean isConfiguration(ResourceDescriptor target) {
        return FileConfigEntities.isFile(target) || ResourceDescriptor.PLATFORM_LOCATION.equals(target.getBucketLocation());
    }

    private static boolean isUserBucket(String location) {
        return location.startsWith(USER_BUCKET_PREFIX) || location.startsWith(API_KEY_BUCKET_PREFIX);
    }

    private Set<ResourceDescriptor> selectReadable(Set<ResourceDescriptor> resources) {
        Map<ResourceDescriptor, Set<ResourceAccessType>> permissions = initiatorPermissions.apply(resources);
        Set<ResourceDescriptor> readable = new HashSet<>();
        for (ResourceDescriptor resource : resources) {
            if (permissions.getOrDefault(resource, Set.of()).contains(ResourceAccessType.READ)) {
                readable.add(resource);
            }
        }
        return readable;
    }

    /** A Model or an Application of the configuration needs a role that admits the caller; an Interceptor and a schema are listed. */
    private boolean isAdmittedConfigurationEntity(ResourceDescriptor target) {
        if (!ROLE_CHECKED_TYPES.contains(DependencyTypePolicy.resolveType(target))) {
            return true;
        }
        Deployment deployment = FileConfigEntities.isFile(target) ? FileConfigEntities.findDeployment(fileConfigSource.get(), target)
                : DependencyTypePolicy.findDeployment(mergedConfigSource.get(), DependencyTypePolicy.resolveType(target), target.getName());
        return deployment != null && deployment.hasAccess(userRoles);
    }
}

package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The policy of an explicit admin: {@code public/}, {@code platform/} and the file configuration. A resource in {@code public/} is
 * checked for the right to read, an explicit admin holds it for every resource there, so the check is a guard. {@code platform/} and the
 * file configuration are open to the admin gate that the caller has passed. The roles of a deployment are not checked, and every other
 * location (a user bucket, a review bucket, an app bucket) is unsupported.
 */
public final class AdminAccessPolicy implements DependencyAccessPolicy {

    private final Predicate<ResourceDescriptor> canReadPublic;

    /**
     * @param canReadPublic whether the caller may read a resource in {@code public/}
     */
    public AdminAccessPolicy(Predicate<ResourceDescriptor> canReadPublic) {
        this.canReadPublic = canReadPublic;
    }

    @Override
    public boolean isRootAllowed(ResourceDescriptor root) {
        ResourceTypes type = DependencyTypePolicy.resolveType(root);
        return !root.isFolder() && DependencyTypePolicy.isRoot(type) && DependencyTypePolicy.isSupportedDependency(type, root.getBucketLocation())
                && isReadable(root);
    }

    @Override
    public boolean isSupported(ResourceDescriptor target) {
        return !target.isFolder() && DependencyTypePolicy.isSupportedDependency(DependencyTypePolicy.resolveType(target), target.getBucketLocation());
    }

    @Override
    public Set<ResourceDescriptor> selectListable(Set<ResourceDescriptor> targets) {
        Set<ResourceDescriptor> allowed = new LinkedHashSet<>();
        for (ResourceDescriptor target : targets) {
            if (isListable(target)) {
                allowed.add(target);
            }
        }
        return allowed;
    }

    private boolean isListable(ResourceDescriptor target) {
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
        return DependencyTypePolicy.isSupportedDependency(type, target.getBucketLocation()) && isReadable(target);
    }

    private boolean isReadable(ResourceDescriptor resource) {
        return !ResourceDescriptor.PUBLIC_LOCATION.equals(resource.getBucketLocation()) || canReadPublic.test(resource);
    }
}

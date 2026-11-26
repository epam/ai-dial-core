package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;

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
        return DependencyTypePolicy.isRoot(DependencyTypePolicy.resolveType(root)) && !FileConfigEntities.isFile(root) && isSupported(root)
                && isReadable(root);
    }

    @Override
    public boolean isSupported(ResourceDescriptor target) {
        return DependencyTypePolicy.isSupported(target);
    }

    @Override
    public Set<ResourceDescriptor> selectListable(Set<ResourceDescriptor> targets, ConfigSnapshot configuration) {
        Set<ResourceDescriptor> allowed = new LinkedHashSet<>();
        for (ResourceDescriptor target : targets) {
            if (isSupported(target) && isReadable(target)) {
                allowed.add(target);
            }
        }
        return allowed;
    }

    private boolean isReadable(ResourceDescriptor resource) {
        return !ResourceDescriptor.PUBLIC_LOCATION.equals(resource.getBucketLocation()) || canReadPublic.test(resource);
    }
}

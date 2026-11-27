package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The policy of an explicit admin: {@code public/}, {@code platform/} and the file configuration, all open to the admin gate that the
 * caller has passed, as they are for every other admin endpoint. The roles of a deployment are not checked, and every other location (a
 * user bucket, a review bucket, an app bucket) is unsupported.
 */
public final class AdminAccessPolicy implements DependencyAccessPolicy {

    @Override
    public Set<ResourceDescriptor> selectAllowedRoots(Set<ResourceDescriptor> roots) {
        Set<ResourceDescriptor> allowed = new LinkedHashSet<>();
        for (ResourceDescriptor root : roots) {
            if (DependencyTypePolicy.isRoot(DependencyTypePolicy.resolveType(root)) && !FileConfigEntities.isFile(root) && isSupported(root)) {
                allowed.add(root);
            }
        }
        return allowed;
    }

    @Override
    public boolean isSupported(ResourceDescriptor target) {
        return DependencyTypePolicy.isSupported(target);
    }

    @Override
    public Set<ResourceDescriptor> selectListable(Set<ResourceDescriptor> targets, ConfigSnapshot configuration) {
        Set<ResourceDescriptor> allowed = new LinkedHashSet<>();
        for (ResourceDescriptor target : targets) {
            if (isSupported(target)) {
                allowed.add(target);
            }
        }
        return allowed;
    }
}


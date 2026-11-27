package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;

import java.util.Set;

/** What a caller may see in the dependency list. Each resource is decided on its own: the right to use a root never covers its dependencies. */
public interface DependencyAccessPolicy {

    /** A reference to anything else is logged as unsupported; a supported one may still be left out by {@link #selectListable}. */
    boolean isSupported(ResourceDescriptor target);

    /**
     * Called once for all the roots of a request, so that a lookup of the right to read is made once and not for each root.
     *
     * @return the roots that the caller may name; the others are refused, and a caller is not told which of them exist
     */
    Set<ResourceDescriptor> selectAllowedRoots(Set<ResourceDescriptor> roots);

    /**
     * Called once for a whole level of the walk; a target that is not returned is left out without a trace.
     *
     * @param configuration the snapshot of the walk
     */
    Set<ResourceDescriptor> selectListable(Set<ResourceDescriptor> targets, ConfigSnapshot configuration);

    /** An entity of the file configuration never has a URL. */
    default boolean isUrlExposed(ResourceDescriptor target) {
        return !FileConfigEntities.isFile(target);
    }
}

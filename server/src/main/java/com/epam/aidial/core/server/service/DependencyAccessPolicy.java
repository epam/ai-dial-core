package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;

import java.util.Set;

/** What a caller may see in the dependency list. Each resource is decided on its own: the right to use a root never covers its dependencies. */
public interface DependencyAccessPolicy {

    /** A reference to anything else is logged as unsupported; a supported one may still be left out by {@link #selectListable}. */
    boolean isSupported(ResourceDescriptor target);


    boolean isRootAllowed(ResourceDescriptor root);

    /** Called once for a whole level of the walk; a target that is not returned is left out without a trace. */
    Set<ResourceDescriptor> selectListable(Set<ResourceDescriptor> targets);

    /** An entity of the file configuration never has a URL. */
    default boolean isUrlExposed(ResourceDescriptor target) {
        return !FileConfigEntities.isFile(target);
    }
}

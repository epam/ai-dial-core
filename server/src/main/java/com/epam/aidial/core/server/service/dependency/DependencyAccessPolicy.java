package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;

import java.util.Set;
import javax.annotation.Nullable;

/** What a caller may see in the dependency list. Each resource is decided on its own: the right to use a root never covers its dependencies. */
public interface DependencyAccessPolicy {

    /**
     * The configuration that one walk resolves every name against, so that a decision about a deployment is taken on the same publication
     * as the discovery of its references. Neither configuration may be changed.
     *
     * @param merged the merged configuration
     * @param file   the file-sourced configuration, or {@code null} while the store has none
     */
    record ConfigSnapshot(Config merged, @Nullable Config file) {
    }

    /** A reference to anything else is logged as unsupported; a supported one may still be left out by {@link #selectListable}. */
    boolean isSupported(ResourceDescriptor target);

    boolean isRootAllowed(ResourceDescriptor root);

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

package com.epam.aidial.core.server.alias;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.ResourceDependency;

import java.util.List;

/**
 * The deployment that serves one call of an {@link Alias}: named like the alias, and forwarding to Core's own
 * address for the target, so that the target runs as a second Core pass.
 */
public interface AliasPass {

    Alias getAlias();

    /**
     * The name of the deployment the alias forwards to, which the target pass is lent the right to call.
     */
    String getTargetName();

    /**
     * Whether the target is a resource, named by its url, rather than a config deployment.
     */
    boolean isResourceTarget();

    /**
     * The alias's target as a resource-dependency list of one entry: {@code READ} on the target's url, which the alias
     * pass grants the target pass in its per-request key. Not admin-consented and not checked against the caller's own
     * reach, as an application's dependencies are: the alias's {@code userRoles} are the authority.
     */
    List<ResourceDependency> getTargetDependencies();
}

package com.epam.aidial.core.server.alias;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.ResourceDependency;
import lombok.Getter;

import java.util.List;

/**
 * The pass of an alias shown as a model or an application. An {@link Application}, so that the alias pass is neither
 * limit-checked nor charged: only the target pass is.
 */
@Getter
class AliasApplicationPass extends Application implements AliasPass {

    private final transient Alias alias;
    private final transient String targetName;
    private final transient boolean resourceTarget;
    private final transient List<ResourceDependency> targetDependencies;

    AliasApplicationPass(Alias alias, String targetName, boolean resourceTarget, List<ResourceDependency> targetDependencies) {
        this.alias = alias;
        this.targetName = targetName;
        this.resourceTarget = resourceTarget;
        this.targetDependencies = targetDependencies;
    }
}

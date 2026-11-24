package com.epam.aidial.core.server.alias;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.ResourceDependency;
import com.epam.aidial.core.config.ToolSet;
import lombok.Getter;

import java.util.List;

/**
 * The pass of an alias shown as a toolset.
 */
@Getter
class AliasToolSetPass extends ToolSet implements AliasPass {

    private final transient Alias alias;
    private final transient String targetName;
    private final transient boolean resourceTarget;
    private final transient List<ResourceDependency> targetDependencies;

    AliasToolSetPass(Alias alias, String targetName, boolean resourceTarget, List<ResourceDependency> targetDependencies) {
        this.alias = alias;
        this.targetName = targetName;
        this.resourceTarget = resourceTarget;
        this.targetDependencies = targetDependencies;
    }
}

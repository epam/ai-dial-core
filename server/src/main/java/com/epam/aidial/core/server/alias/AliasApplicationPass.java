package com.epam.aidial.core.server.alias;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.Application;
import lombok.Getter;

/**
 * The pass of an alias shown as a model or an application. An {@link Application}, so that the alias pass is neither
 * limit-checked nor charged: only the target pass is.
 */
@Getter
class AliasApplicationPass extends Application implements AliasPass {

    private final transient Alias alias;
    private final transient String targetName;
    private final transient boolean resourceTarget;

    AliasApplicationPass(Alias alias, String targetName, boolean resourceTarget) {
        this.alias = alias;
        this.targetName = targetName;
        this.resourceTarget = resourceTarget;
    }
}

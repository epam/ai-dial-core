package com.epam.aidial.core.server.alias;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.ToolSet;
import lombok.Getter;

/**
 * The pass of an alias shown as a toolset.
 */
@Getter
class AliasToolSetPass extends ToolSet implements AliasPass {

    private final transient Alias alias;
    private final transient String targetName;
    private final transient boolean resourceTarget;

    AliasToolSetPass(Alias alias, String targetName, boolean resourceTarget) {
        this.alias = alias;
        this.targetName = targetName;
        this.resourceTarget = resourceTarget;
    }
}

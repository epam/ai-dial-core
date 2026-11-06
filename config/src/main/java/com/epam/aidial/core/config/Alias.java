package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * A deployment of its own (name, catalog entry, {@code userRoles}) that forwards calls to the deployment its
 * {@link #target} names and grants the caller the right to call it. Only the display fields of {@link Deployment}
 * apply to an alias; pricing, features and interfaces come from the target.
 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Alias extends Deployment {

    // optional: the type of the target when not set
    @JsonAlias({"presentAs", "present_as"})
    private AliasPresentation presentAs;

    private AliasTarget target;

    /**
     * The type the alias is listed and called as: {@link #presentAs}, or else the type the first segment of the target
     * path names. Null when neither says.
     */
    @JsonIgnore
    public AliasPresentation getEffectivePresentation() {
        if (presentAs != null) {
            return presentAs;
        }
        String path = target == null ? null : target.getPath();
        if (path == null) {
            return null;
        }
        int slash = path.indexOf('/');
        return switch (slash < 0 ? path : path.substring(0, slash)) {
            case "models" -> AliasPresentation.MODEL;
            case "applications" -> AliasPresentation.APPLICATION;
            case "toolsets" -> AliasPresentation.TOOLSET;
            default -> null;
        };
    }
}

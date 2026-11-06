package com.epam.aidial.core.server.alias;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.AliasPresentation;
import com.epam.aidial.core.config.AliasTarget;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.config.ValidationWarning;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Reads and checks the {@code target} of an {@link Alias}: its shape, the type of deployment it names, and whether
 * the alias's effective presentation fits that type.
 */
@UtilityClass
public class AliasTargets {

    private static final String PATH_FIELD = "target.path";

    /**
     * A parsed {@code target.path}: the deployment type and the name of a config deployment.
     */
    public record TargetRef(ResourceTypes type, String name) {
    }

    /**
     * The target path as a config deployment reference, or null when the path is not
     * {@code <models|applications|toolsets>/<name>}.
     */
    @Nullable
    public TargetRef parse(@Nullable String path) {
        if (path == null) {
            return null;
        }
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1 || path.indexOf('/', slash + 1) >= 0) {
            return null;
        }
        String name = path.substring(slash + 1);
        return switch (path.substring(0, slash)) {
            case "models" -> new TargetRef(ResourceTypes.MODEL, name);
            case "applications" -> new TargetRef(ResourceTypes.APPLICATION, name);
            case "toolsets" -> new TargetRef(ResourceTypes.TOOL_SET, name);
            default -> null;
        };
    }

    /**
     * The config deployment the reference names, or null when the config has none of that type and name.
     */
    @Nullable
    public Deployment resolve(Config config, TargetRef ref) {
        return switch (ref.type()) {
            case MODEL -> config.getModels().get(ref.name());
            case APPLICATION -> config.getApplications().get(ref.name());
            case TOOL_SET -> config.getToolsets().get(ref.name());
            default -> null;
        };
    }

    /**
     * Whether an alias shown as {@code presentation} can stand for {@code target}: a model or an application for
     * {@code model} and {@code application}, a toolset or an application with {@code mcp} for {@code toolset}.
     */
    public boolean fits(AliasPresentation presentation, Deployment target) {
        return switch (presentation) {
            case MODEL, APPLICATION -> target instanceof Model || target instanceof Application;
            case TOOLSET -> target instanceof ToolSet || target instanceof Application app && app.getMcp() != null;
        };
    }

    /**
     * Whether the target path is a resource url, {@code <type>/<bucket>/<path>}, rather than
     * the name of a config deployment.
     */
    public boolean isResourcePath(String path) {
        int first = path.indexOf('/');
        return first > 0 && path.indexOf('/', first + 1) > first + 1;
    }

    /**
     * The problems of the alias's own fields: a {@code target} with a {@code path} that names a deployment of the config
     * or an application or toolset resource, and not another alias. The target itself is not looked up.
     */
    public List<ValidationWarning> validateFields(Alias alias) {
        List<ValidationWarning> warnings = new ArrayList<>();
        AliasTarget target = alias.getTarget();
        if (target == null || target.getPath() == null) {
            warnings.add(new ValidationWarning(PATH_FIELD, "target.path is required"));
            return warnings;
        }
        String path = target.getPath();
        if (path.startsWith("aliases/")) {
            warnings.add(new ValidationWarning(PATH_FIELD, "An alias cannot point to another alias: " + path));
        } else if (isResourcePath(path)) {
            if (!path.startsWith("applications/") && !path.startsWith("toolsets/")) {
                warnings.add(new ValidationWarning(PATH_FIELD, "target.path must be an application or a toolset: " + path));
            }
        } else if (parse(path) == null) {
            warnings.add(new ValidationWarning(PATH_FIELD,
                    "target.path must be models/<name>, applications/<name>, toolsets/<name> or a resource url: " + path));
        }
        return warnings;
    }

    /**
     * The problems that make an alias invalid whatever else the config holds: {@link #validateFields}, and a type
     * that does not fit a config target the config has. A config target the config does not have is not one of them:
     * {@link #isTargetMissing} reports it, and it fails only the calls of the alias. A resource target is read on each
     * call, so it is not looked up here, nor is the type of a schema-based application, whose {@code mcp} comes from
     * its schema.
     */
    public List<ValidationWarning> validate(Alias alias, Config config) {
        List<ValidationWarning> warnings = validateFields(alias);
        if (!warnings.isEmpty()) {
            return warnings;
        }
        String path = alias.getTarget().getPath();
        if (isResourcePath(path)) {
            return warnings;
        }
        Deployment target = resolve(config, parse(path));
        AliasPresentation presentation = alias.getEffectivePresentation();
        // a schema-based application gets its mcp from the schema, which only a call reads
        boolean schemaBased = target instanceof Application application && application.hasApplicationTypeSchemaId();
        if (target != null && !schemaBased && !fits(presentation, target)) {
            warnings.add(new ValidationWarning("presentAs",
                    "An alias shown as %s cannot point to %s".formatted(presentation.getValue(), path)));
        }
        return warnings;
    }

    /**
     * Whether a valid alias's config target is in the config but no longer fits its type: an API write changed the
     * target after the alias was loaded. False for a resource target and for a schema-based application.
     */
    public boolean isTargetIncompatible(Alias alias, Config config) {
        String path = alias.getTarget().getPath();
        if (isResourcePath(path)) {
            return false;
        }
        Deployment target = resolve(config, parse(path));
        boolean schemaBased = target instanceof Application application && application.hasApplicationTypeSchemaId();
        return target != null && !schemaBased && !fits(alias.getEffectivePresentation(), target);
    }

    /**
     * Whether the config lacks the deployment a valid alias points to.
     */
    public boolean isTargetMissing(Alias alias, Config config) {
        String path = alias.getTarget().getPath();
        return !isResourcePath(path) && resolve(config, parse(path)) == null;
    }
}

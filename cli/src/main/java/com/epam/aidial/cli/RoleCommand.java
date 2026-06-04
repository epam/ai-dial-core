package com.epam.aidial.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.UseDefaultConverter;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "role",
        description = "Manage DIAL role entities.",
        mixinStandardHelpOptions = true,
        subcommands = {RoleCommand.Get.class, RoleCommand.List.class,
                RoleCommand.Add.class, RoleCommand.Update.class,
                RoleCommand.Delete.class, RoleCommand.Validate.class,
                RoleCommand.Promote.class, RoleCommand.Diff.class}
)
public class RoleCommand {

    static final String TYPE = "roles";
    static final String BUCKET = "platform";
    static final String KIND = "Role";
    static final String CANONICAL_PREFIX = TYPE + "/" + BUCKET + "/";

    @ParentCommand
    DialCli parent;

    @Command(name = "get",
            description = "Get a single role. Pass a canonical id (roles/<bucket>/<name>) for API-managed entities, or a plain name for file-config entities.")
    static class Get implements Callable<Integer> {
        @ParentCommand
        RoleCommand cmd;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Role name or canonical id (roles/<bucket>/<name>).")
        String name;

        @Override
        public Integer call() {
            EntityReader.readEntity(cmd.parent, spec, TYPE, name);
            return 0;
        }
    }

    @Command(name = "list", description = "List roles from all sources (API-managed and file-config).")
    static class List implements Callable<Integer> {
        @ParentCommand
        RoleCommand cmd;
        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            EntityReader.listEntities(cmd.parent, spec, TYPE);
            return 0;
        }
    }

    @Command(name = "add", description = "Create a role (PUT with If-None-Match: *). Fails with exit 5 if it already exists.")
    static class Add implements Callable<Integer> {
        @ParentCommand
        RoleCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--name", required = true,
                description = "Canonical id (roles/platform/<name>).")
        String name;
        @Option(names = "--from-file", required = true,
                description = "JSON or YAML file with the role spec (.yaml/.yml parsed as YAML).")
        Path fromFile;
        @Option(names = "--template", description = "Template name from CLI profile to apply.")
        String template;
        @Option(names = "--param", description = "Template parameter 'key=value' (repeatable).", converter = {UseDefaultConverter.class, ParamValueConverter.class})
        Map<String, Object> params = new HashMap<>();

        @Override
        public Integer call() {
            EntityWriter.addEntity(cmd.parent, spec, TYPE, KIND, BUCKET, name, fromFile, template, params);
            return 0;
        }
    }

    @Command(name = "update",
            description = "Update a role (PUT). Fails with exit 4 if it does not exist, 6 on stale ETag.")
    static class Update implements Callable<Integer> {
        @ParentCommand
        RoleCommand cmd;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Canonical id (roles/platform/<name>).")
        String name;
        @Option(names = "--set", description = "Field override 'dot.path=value' (repeatable). Keys are dot-paths; values are JSON-coerced.",
                converter = {UseDefaultConverter.class, JsonNodeValueConverter.class})
        Map<String, JsonNode> sets;
        @Option(names = "--if-match", description = "ETag for optimistic concurrency. Defaults to the GET response's ETag.")
        String ifMatch;

        @Override
        public Integer call() {
            EntityWriter.updateEntity(cmd.parent, spec, TYPE, BUCKET, name, sets, ifMatch);
            return 0;
        }
    }

    @Command(name = "delete", description = "Delete a role (DELETE). Fails with exit 4 if missing, 6 on stale ETag.")
    static class Delete implements Callable<Integer> {
        @ParentCommand
        RoleCommand cmd;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Canonical id (roles/platform/<name>).")
        String name;
        @Option(names = "--if-match", description = "ETag for optimistic concurrency.")
        String ifMatch;

        @Override
        public Integer call() {
            EntityWriter.deleteEntity(cmd.parent, spec, TYPE, BUCKET, name, ifMatch);
            return 0;
        }
    }

    @Command(name = "validate",
            description = "Validate a proposed role spec via POST /v1/admin/validate (no write).")
    static class Validate implements Callable<Integer> {
        @ParentCommand
        RoleCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--name", required = true,
                description = "Canonical id (roles/platform/<name>).")
        String name;
        @Option(names = "--from-file", required = true,
                description = "JSON or YAML file with the role spec (.yaml/.yml parsed as YAML).")
        Path fromFile;
        @Option(names = "--template", description = "Template name from CLI profile to apply.")
        String template;
        @Option(names = "--param", description = "Template parameter 'key=value' (repeatable).", converter = {UseDefaultConverter.class, ParamValueConverter.class})
        Map<String, Object> params = new HashMap<>();

        @Override
        public Integer call() {
            return EntityWriter.validateEntity(cmd.parent, spec, TYPE, KIND, BUCKET, name, fromFile, template, params);
        }
    }

    @Command(name = "promote",
            description = "Promote a role from one environment to another via POST /v1/admin/apply.")
    static class Promote implements Callable<Integer> {
        @ParentCommand
        RoleCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--from", required = true, description = "Source environment.")
        String fromEnv;
        @Option(names = "--to", required = true, description = "Target environment.")
        String toEnv;
        @Option(names = "--name", required = true,
                description = "Canonical id (roles/platform/<name>).")
        String name;
        @Option(names = "--template",
                description = "Template name from CLI profile, or 'auto' to reverse-match against the source entity.")
        String template;
        @Option(names = "--param", description = "Template parameter 'key=value' (repeatable).", converter = {UseDefaultConverter.class, ParamValueConverter.class})
        Map<String, Object> params = new HashMap<>();

        @Override
        public Integer call() {
            return EntityWriter.promoteEntity(cmd.parent, spec, TYPE, KIND, BUCKET, name, fromEnv, toEnv, template, params);
        }
    }

    @Command(name = "diff",
            description = "Structural diff of a single role (with --name) or all roles between two environments.")
    static class Diff implements Callable<Integer> {

        @ParentCommand
        RoleCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--source", required = true, description = "Source environment.")
        String sourceEnv;
        @Option(names = "--target", required = true, description = "Target environment.")
        String targetEnv;
        @Option(names = "--name", description = "Optional canonical id (roles/platform/<name>) for a single-entity diff.")
        String name;

        @Override
        public Integer call() {
            return EntityDiff.run(cmd.parent, spec, TYPE, BUCKET, CANONICAL_PREFIX, sourceEnv, targetEnv, name);
        }
    }
}

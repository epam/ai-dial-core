package com.epam.aidial.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "route",
        description = "Manage DIAL route entities.",
        mixinStandardHelpOptions = true,
        subcommands = {RouteCommand.Get.class, RouteCommand.List.class,
                RouteCommand.Add.class, RouteCommand.Update.class,
                RouteCommand.Delete.class, RouteCommand.Validate.class,
                RouteCommand.Promote.class, RouteCommand.Diff.class}
)
public class RouteCommand {

    static final String TYPE = "routes";
    static final String BUCKET = "platform";
    static final String KIND = "Route";
    static final String CANONICAL_PREFIX = TYPE + "/" + BUCKET + "/";

    @ParentCommand
    DialCli parent;

    @Command(name = "get", description = "Get a single route by name (or canonical id).")
    static class Get implements Callable<Integer> {
        @ParentCommand
        RouteCommand cmd;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Route name or canonical id (routes/<bucket>/<name>).")
        String name;

        @Override
        public Integer call() {
            return EntityReader.readEntity(cmd.parent, spec, TYPE, name);
        }
    }

    @Command(name = "list", description = "List routes in the platform bucket.")
    static class List implements Callable<Integer> {
        @ParentCommand
        RouteCommand cmd;
        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            return EntityReader.listEntities(cmd.parent, spec, TYPE);
        }
    }

    @Command(name = "add", description = "Create a route (POST). Fails with exit 5 if it already exists.")
    static class Add implements Callable<Integer> {
        @ParentCommand
        RouteCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--name", required = true,
                description = "Canonical id (routes/platform/<name>).")
        String name;
        @Option(names = "--from-file", required = true,
                description = "JSON or YAML file with the route spec (.yaml/.yml parsed as YAML).")
        Path fromFile;
        @Option(names = "--template", description = "Template name from CLI profile to apply.")
        String template;
        @Option(names = "--param", description = "Template parameter 'key=value' (repeatable).")
        java.util.List<String> params;

        @Override
        public Integer call() {
            return EntityWriter.addEntity(cmd.parent, spec, TYPE, KIND, BUCKET, name, fromFile, template, params);
        }
    }

    @Command(name = "update",
            description = "Update a route (PUT). Fails with exit 4 if it does not exist, 6 on stale ETag.")
    static class Update implements Callable<Integer> {
        @ParentCommand
        RouteCommand cmd;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Canonical id (routes/platform/<name>).")
        String name;
        @Option(names = "--set", description = "Field override 'path=value' (repeatable). Dotted paths nest; values are JSON-coerced.")
        java.util.List<String> sets;
        @Option(names = "--if-match", description = "ETag for optimistic concurrency. Defaults to the GET response's ETag.")
        String ifMatch;

        @Override
        public Integer call() {
            return EntityWriter.updateEntity(cmd.parent, spec, TYPE, BUCKET, name, sets, ifMatch);
        }
    }

    @Command(name = "delete", description = "Delete a route (DELETE). Fails with exit 4 if missing, 6 on stale ETag.")
    static class Delete implements Callable<Integer> {
        @ParentCommand
        RouteCommand cmd;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Canonical id (routes/platform/<name>).")
        String name;
        @Option(names = "--if-match", description = "ETag for optimistic concurrency.")
        String ifMatch;

        @Override
        public Integer call() {
            return EntityWriter.deleteEntity(cmd.parent, spec, TYPE, BUCKET, name, ifMatch);
        }
    }

    @Command(name = "validate",
            description = "Validate a proposed route spec via POST /v1/admin/validate (no write).")
    static class Validate implements Callable<Integer> {
        @ParentCommand
        RouteCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--name", required = true,
                description = "Canonical id (routes/platform/<name>).")
        String name;
        @Option(names = "--from-file", required = true,
                description = "JSON or YAML file with the route spec (.yaml/.yml parsed as YAML).")
        Path fromFile;
        @Option(names = "--template", description = "Template name from CLI profile to apply.")
        String template;
        @Option(names = "--param", description = "Template parameter 'key=value' (repeatable).")
        java.util.List<String> params;

        @Override
        public Integer call() {
            return EntityWriter.validateEntity(cmd.parent, spec, TYPE, KIND, BUCKET, name, fromFile, template, params);
        }
    }

    @Command(name = "promote",
            description = "Promote a route from one environment to another via POST /v1/admin/apply.")
    static class Promote implements Callable<Integer> {
        @ParentCommand
        RouteCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--from", required = true, description = "Source environment.")
        String fromEnv;
        @Option(names = "--to", required = true, description = "Target environment.")
        String toEnv;
        @Option(names = "--name", required = true,
                description = "Canonical id (routes/platform/<name>).")
        String name;

        @Override
        public Integer call() {
            return EntityWriter.promoteEntity(cmd.parent, spec, TYPE, KIND, BUCKET, name, fromEnv, toEnv);
        }
    }

    @Command(name = "diff",
            description = "Structural diff of a single route (with --name) or all routes between two environments.")
    static class Diff implements Callable<Integer> {

        @ParentCommand
        RouteCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--source", required = true, description = "Source environment.")
        String sourceEnv;
        @Option(names = "--target", required = true, description = "Target environment.")
        String targetEnv;
        @Option(names = "--name", description = "Optional canonical id (routes/platform/<name>) for a single-entity diff.")
        String name;

        @Override
        public Integer call() {
            return EntityDiff.run(cmd.parent, spec, TYPE, BUCKET, CANONICAL_PREFIX, sourceEnv, targetEnv, name);
        }
    }
}

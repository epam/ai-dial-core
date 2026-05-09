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
        name = "interceptor",
        description = "Manage DIAL interceptor entities.",
        mixinStandardHelpOptions = true,
        subcommands = {InterceptorCommand.Get.class, InterceptorCommand.List.class,
                InterceptorCommand.Add.class, InterceptorCommand.Update.class,
                InterceptorCommand.Delete.class, InterceptorCommand.Validate.class,
                InterceptorCommand.Promote.class, InterceptorCommand.Diff.class}
)
public class InterceptorCommand {

    static final String TYPE = "interceptors";
    static final String BUCKET = "platform";
    static final String KIND = "Interceptor";
    static final String CANONICAL_PREFIX = TYPE + "/" + BUCKET + "/";

    @ParentCommand
    DialCli parent;

    @Command(name = "get", description = "Get a single interceptor by name (or canonical id).")
    static class Get implements Callable<Integer> {
        @ParentCommand
        InterceptorCommand cmd;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Interceptor name or canonical id (interceptors/<bucket>/<name>).")
        String name;

        @Override
        public Integer call() {
            return EntityReader.readEntity(cmd.parent, spec, TYPE, name);
        }
    }

    @Command(name = "list", description = "List interceptors in the platform bucket.")
    static class List implements Callable<Integer> {
        @ParentCommand
        InterceptorCommand cmd;
        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            return EntityReader.listEntities(cmd.parent, spec, TYPE);
        }
    }

    @Command(name = "add", description = "Create an interceptor (POST). Fails with exit 5 if it already exists.")
    static class Add implements Callable<Integer> {
        @ParentCommand
        InterceptorCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--name", required = true,
                description = "Canonical id (interceptors/platform/<name>).")
        String name;
        @Option(names = "--from-file", required = true,
                description = "JSON or YAML file with the interceptor spec (.yaml/.yml parsed as YAML).")
        Path fromFile;

        @Override
        public Integer call() {
            return EntityWriter.addEntity(cmd.parent, spec, TYPE, KIND, BUCKET, name, fromFile);
        }
    }

    @Command(name = "update",
            description = "Update an interceptor (PUT). Fails with exit 4 if it does not exist, 6 on stale ETag.")
    static class Update implements Callable<Integer> {
        @ParentCommand
        InterceptorCommand cmd;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Canonical id (interceptors/platform/<name>).")
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

    @Command(name = "delete", description = "Delete an interceptor (DELETE). Fails with exit 4 if missing, 6 on stale ETag.")
    static class Delete implements Callable<Integer> {
        @ParentCommand
        InterceptorCommand cmd;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Canonical id (interceptors/platform/<name>).")
        String name;
        @Option(names = "--if-match", description = "ETag for optimistic concurrency.")
        String ifMatch;

        @Override
        public Integer call() {
            return EntityWriter.deleteEntity(cmd.parent, spec, TYPE, BUCKET, name, ifMatch);
        }
    }

    @Command(name = "validate",
            description = "Validate a proposed interceptor spec via POST /v1/admin/validate (no write).")
    static class Validate implements Callable<Integer> {
        @ParentCommand
        InterceptorCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--name", required = true,
                description = "Canonical id (interceptors/platform/<name>).")
        String name;
        @Option(names = "--from-file", required = true,
                description = "JSON or YAML file with the interceptor spec (.yaml/.yml parsed as YAML).")
        Path fromFile;

        @Override
        public Integer call() {
            return EntityWriter.validateEntity(cmd.parent, spec, TYPE, KIND, BUCKET, name, fromFile);
        }
    }

    @Command(name = "promote",
            description = "Promote an interceptor from one environment to another via POST /v1/admin/apply.")
    static class Promote implements Callable<Integer> {
        @ParentCommand
        InterceptorCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--from", required = true, description = "Source environment.")
        String fromEnv;
        @Option(names = "--to", required = true, description = "Target environment.")
        String toEnv;
        @Option(names = "--name", required = true,
                description = "Canonical id (interceptors/platform/<name>).")
        String name;

        @Override
        public Integer call() {
            return EntityWriter.promoteEntity(cmd.parent, spec, TYPE, KIND, BUCKET, name, fromEnv, toEnv);
        }
    }

    @Command(name = "diff",
            description = "Structural diff of a single interceptor (with --name) or all interceptors between two environments.")
    static class Diff implements Callable<Integer> {

        @ParentCommand
        InterceptorCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--source", required = true, description = "Source environment.")
        String sourceEnv;
        @Option(names = "--target", required = true, description = "Target environment.")
        String targetEnv;
        @Option(names = "--name", description = "Optional canonical id (interceptors/platform/<name>) for a single-entity diff.")
        String name;

        @Override
        public Integer call() {
            return EntityDiff.run(cmd.parent, spec, TYPE, BUCKET, CANONICAL_PREFIX, sourceEnv, targetEnv, name);
        }
    }
}

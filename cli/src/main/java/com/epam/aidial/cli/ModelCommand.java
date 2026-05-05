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
        name = "model",
        description = "Manage DIAL model entities.",
        mixinStandardHelpOptions = true,
        subcommands = {ModelCommand.Get.class, ModelCommand.List.class, ModelCommand.Add.class,
                ModelCommand.Update.class, ModelCommand.Delete.class, ModelCommand.Validate.class,
                ModelCommand.Promote.class}
)
public class ModelCommand {

    @ParentCommand
    DialCli parent;

    @Command(name = "get", description = "Get a single model by name (or canonical id).")
    static class Get implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Model name or canonical id (models/<bucket>/<name>).")
        String name;

        @Override
        public Integer call() {
            return EntityReader.readEntity(model.parent, spec, "models", name);
        }
    }

    @Command(name = "list", description = "List models in the public bucket.")
    static class List implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            return EntityReader.listEntities(model.parent, spec, "models");
        }
    }

    @Command(name = "add", description = "Create a model (POST). Fails with exit 5 if it already exists.")
    static class Add implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;
        @Option(names = "--name", required = true,
                description = "Canonical id (models/public/<name>).")
        String name;
        @Option(names = "--from-file", required = true,
                description = "JSON or YAML file with the model spec (.yaml/.yml parsed as YAML).")
        Path fromFile;

        @Override
        public Integer call() {
            return EntityWriter.addEntity(model.parent, spec, "models", name, fromFile);
        }
    }

    @Command(name = "update",
            description = "Update a model (PUT). Fails with exit 4 if it does not exist, 6 on stale ETag.")
    static class Update implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Canonical id (models/public/<name>).")
        String name;
        @Option(names = "--set", description = "Field override 'path=value' (repeatable). Dotted paths nest; values are JSON-coerced.")
        java.util.List<String> sets;
        @Option(names = "--if-match", description = "ETag for optimistic concurrency. Defaults to the GET response's ETag.")
        String ifMatch;

        @Override
        public Integer call() {
            return EntityWriter.updateEntity(model.parent, spec, "models", name, sets, ifMatch);
        }
    }

    @Command(name = "delete", description = "Delete a model (DELETE). Fails with exit 4 if missing, 6 on stale ETag.")
    static class Delete implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Canonical id (models/public/<name>).")
        String name;
        @Option(names = "--if-match", description = "ETag for optimistic concurrency.")
        String ifMatch;

        @Override
        public Integer call() {
            return EntityWriter.deleteEntity(model.parent, spec, "models", name, ifMatch);
        }
    }

    @Command(name = "validate",
            description = "Validate a proposed model spec via POST /v1/admin/validate (no write).")
    static class Validate implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;
        @Option(names = "--name", required = true,
                description = "Canonical id (models/public/<name>).")
        String name;
        @Option(names = "--from-file", required = true,
                description = "JSON or YAML file with the model spec (.yaml/.yml parsed as YAML).")
        Path fromFile;

        @Override
        public Integer call() {
            return EntityWriter.validateEntity(model.parent, spec, "models", "Model", name, fromFile);
        }
    }

    @Command(name = "promote",
            description = "Promote a model from one environment to another via POST /v1/admin/apply.")
    static class Promote implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;
        @Option(names = "--from", required = true, description = "Source environment.")
        String fromEnv;
        @Option(names = "--to", required = true, description = "Target environment.")
        String toEnv;
        @Option(names = "--name", required = true,
                description = "Canonical id (models/public/<name>).")
        String name;

        @Override
        public Integer call() {
            return EntityWriter.promoteEntity(model.parent, spec, "models", "Model", name, fromEnv, toEnv);
        }
    }
}

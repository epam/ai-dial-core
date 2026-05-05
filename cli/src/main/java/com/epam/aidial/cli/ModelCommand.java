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
        subcommands = {ModelCommand.Get.class, ModelCommand.List.class, ModelCommand.Add.class}
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
}

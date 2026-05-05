package com.epam.aidial.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(
        name = "schema",
        description = "Read DIAL schema entities.",
        mixinStandardHelpOptions = true,
        subcommands = {SchemaCommand.Get.class, SchemaCommand.List.class}
)
public class SchemaCommand {

    @ParentCommand
    DialCli parent;

    @Command(name = "get", description = "Get a single schema by name (or canonical id).")
    static class Get implements Callable<Integer> {
        @ParentCommand
        SchemaCommand cmd;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Schema name or canonical id (schemas/<bucket>/<name>).")
        String name;

        @Override
        public Integer call() {
            return EntityReader.readEntity(cmd.parent, spec, "schemas", name);
        }
    }

    @Command(name = "list", description = "List schemas in the platform bucket.")
    static class List implements Callable<Integer> {
        @ParentCommand
        SchemaCommand cmd;
        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            return EntityReader.listEntities(cmd.parent, spec, "schemas");
        }
    }
}

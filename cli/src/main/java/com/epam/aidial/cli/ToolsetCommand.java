package com.epam.aidial.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(
        name = "toolset",
        description = "Read DIAL toolset entities.",
        mixinStandardHelpOptions = true,
        subcommands = {ToolsetCommand.Get.class, ToolsetCommand.List.class}
)
public class ToolsetCommand {

    @ParentCommand
    DialCli parent;

    @Command(name = "get", description = "Get a single toolset by name (or canonical id).")
    static class Get implements Callable<Integer> {
        @ParentCommand
        ToolsetCommand cmd;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Toolset name or canonical id (toolsets/<bucket>/<name>).")
        String name;

        @Override
        public Integer call() {
            return EntityReader.readEntity(cmd.parent, spec, "toolsets", name);
        }
    }

    @Command(name = "list", description = "List toolsets in the public bucket.")
    static class List implements Callable<Integer> {
        @ParentCommand
        ToolsetCommand cmd;
        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            return EntityReader.listEntities(cmd.parent, spec, "toolsets");
        }
    }
}

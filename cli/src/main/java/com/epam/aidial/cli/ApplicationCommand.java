package com.epam.aidial.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(
        name = "application",
        description = "Read DIAL application entities.",
        mixinStandardHelpOptions = true,
        subcommands = {ApplicationCommand.Get.class, ApplicationCommand.List.class}
)
public class ApplicationCommand {

    @ParentCommand
    DialCli parent;

    @Command(name = "get", description = "Get a single application by name (or canonical id).")
    static class Get implements Callable<Integer> {
        @ParentCommand
        ApplicationCommand cmd;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Application name or canonical id (applications/<bucket>/<name>).")
        String name;

        @Override
        public Integer call() {
            return EntityReader.readEntity(cmd.parent, spec, "applications", name);
        }
    }

    @Command(name = "list", description = "List applications in the public bucket.")
    static class List implements Callable<Integer> {
        @ParentCommand
        ApplicationCommand cmd;
        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            return EntityReader.listEntities(cmd.parent, spec, "applications");
        }
    }
}

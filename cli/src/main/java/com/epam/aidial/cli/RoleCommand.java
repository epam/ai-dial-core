package com.epam.aidial.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(
        name = "role",
        description = "Read DIAL role entities.",
        mixinStandardHelpOptions = true,
        subcommands = {RoleCommand.Get.class, RoleCommand.List.class}
)
public class RoleCommand {

    @ParentCommand
    DialCli parent;

    @Command(name = "get", description = "Get a single role by name (or canonical id).")
    static class Get implements Callable<Integer> {
        @ParentCommand
        RoleCommand cmd;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Role name or canonical id (roles/<bucket>/<name>).")
        String name;

        @Override
        public Integer call() {
            return EntityReader.readEntity(cmd.parent, spec, "roles", name);
        }
    }

    @Command(name = "list", description = "List roles in the platform bucket.")
    static class List implements Callable<Integer> {
        @ParentCommand
        RoleCommand cmd;
        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            return EntityReader.listEntities(cmd.parent, spec, "roles");
        }
    }
}

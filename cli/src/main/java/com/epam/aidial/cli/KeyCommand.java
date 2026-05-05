package com.epam.aidial.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(
        name = "key",
        description = "Read DIAL API key entities.",
        mixinStandardHelpOptions = true,
        subcommands = {KeyCommand.Get.class, KeyCommand.List.class}
)
public class KeyCommand {

    @ParentCommand
    DialCli parent;

    @Command(name = "get", description = "Get a single API key by name (or canonical id).")
    static class Get implements Callable<Integer> {
        @ParentCommand
        KeyCommand cmd;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Key name or canonical id (keys/<bucket>/<name>).")
        String name;

        @Override
        public Integer call() {
            return EntityReader.readEntity(cmd.parent, spec, "keys", name);
        }
    }

    @Command(name = "list", description = "List API keys in the platform bucket.")
    static class List implements Callable<Integer> {
        @ParentCommand
        KeyCommand cmd;
        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            return EntityReader.listEntities(cmd.parent, spec, "keys");
        }
    }
}

package com.epam.aidial.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(
        name = "settings",
        description = "Read DIAL global settings (singleton).",
        mixinStandardHelpOptions = true,
        subcommands = {SettingsCommand.Get.class}
)
public class SettingsCommand {

    @ParentCommand
    DialCli parent;

    @Command(name = "get", description = "Get the effective global settings (no name argument).")
    static class Get implements Callable<Integer> {
        @ParentCommand
        SettingsCommand cmd;
        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            return EntityReader.readSingleton(cmd.parent, spec, "settings");
        }
    }
}

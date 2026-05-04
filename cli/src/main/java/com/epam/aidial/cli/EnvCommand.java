package com.epam.aidial.cli;

import picocli.CommandLine.Command;

@Command(
        name = "env",
        description = "Manage CLI environment profiles.",
        mixinStandardHelpOptions = true,
        subcommands = {
                EnvCommand.List.class,
                EnvCommand.Current.class,
                EnvCommand.Use.class,
                EnvCommand.Check.class
        }
)
public class EnvCommand {

    @Command(name = "list", description = "List configured environments.")
    static class List implements Runnable {
        @Override
        public void run() {
            throw new UnsupportedOperationException("env list — wires up in slice 1C.1");
        }
    }

    @Command(name = "current", description = "Print the currently selected environment.")
    static class Current implements Runnable {
        @Override
        public void run() {
            throw new UnsupportedOperationException("env current — wires up in slice 1C.1");
        }
    }

    @Command(name = "use", description = "Persist defaults.env in the CLI profile.")
    static class Use implements Runnable {
        @Override
        public void run() {
            throw new UnsupportedOperationException("env use — wires up in slice 1C.1");
        }
    }

    @Command(name = "check", description = "Probe API URL + credential resolution for a profile.")
    static class Check implements Runnable {
        @Override
        public void run() {
            throw new UnsupportedOperationException("env check — wires up in slice 1C.1");
        }
    }
}

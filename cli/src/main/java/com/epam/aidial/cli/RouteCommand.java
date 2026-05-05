package com.epam.aidial.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(
        name = "route",
        description = "Read DIAL route entities.",
        mixinStandardHelpOptions = true,
        subcommands = {RouteCommand.Get.class, RouteCommand.List.class}
)
public class RouteCommand {

    @ParentCommand
    DialCli parent;

    @Command(name = "get", description = "Get a single route by name (or canonical id).")
    static class Get implements Callable<Integer> {
        @ParentCommand
        RouteCommand cmd;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Route name or canonical id (routes/<bucket>/<name>).")
        String name;

        @Override
        public Integer call() {
            return EntityReader.readEntity(cmd.parent, spec, "routes", name);
        }
    }

    @Command(name = "list", description = "List routes in the platform bucket.")
    static class List implements Callable<Integer> {
        @ParentCommand
        RouteCommand cmd;
        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            return EntityReader.listEntities(cmd.parent, spec, "routes");
        }
    }
}

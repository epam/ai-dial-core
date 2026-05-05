package com.epam.aidial.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(
        name = "interceptor",
        description = "Read DIAL interceptor entities.",
        mixinStandardHelpOptions = true,
        subcommands = {InterceptorCommand.Get.class, InterceptorCommand.List.class}
)
public class InterceptorCommand {

    @ParentCommand
    DialCli parent;

    @Command(name = "get", description = "Get a single interceptor by name (or canonical id).")
    static class Get implements Callable<Integer> {
        @ParentCommand
        InterceptorCommand cmd;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Interceptor name or canonical id (interceptors/<bucket>/<name>).")
        String name;

        @Override
        public Integer call() {
            return EntityReader.readEntity(cmd.parent, spec, "interceptors", name);
        }
    }

    @Command(name = "list", description = "List interceptors in the platform bucket.")
    static class List implements Callable<Integer> {
        @ParentCommand
        InterceptorCommand cmd;
        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            return EntityReader.listEntities(cmd.parent, spec, "interceptors");
        }
    }
}

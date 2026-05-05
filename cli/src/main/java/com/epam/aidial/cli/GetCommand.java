package com.epam.aidial.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(name = "get", description = "Read entities (kubectl-style alias for <type> list).", mixinStandardHelpOptions = true)
public class GetCommand implements Callable<Integer> {

    @ParentCommand
    DialCli parent;
    @Spec
    CommandSpec spec;
    @Parameters(arity = "0..1", description = "Resource type (e.g. models, roles, keys).")
    String resourceType;

    private static final java.util.Set<String> LIST_TYPES = java.util.Set.of(
            "models", "applications", "toolsets",
            "interceptors", "roles", "keys", "routes", "schemas"
    );

    @Override
    public Integer call() {
        if (resourceType == null) {
            spec.commandLine().getErr().println("Resource type required (e.g. 'dial-cli get models').");
            return 2;
        }
        if (LIST_TYPES.contains(resourceType)) {
            return EntityReader.listEntities(parent, spec, resourceType);
        }
        if ("settings".equals(resourceType)) {
            return EntityReader.readSingleton(parent, spec, "settings");
        }
        spec.commandLine().getErr().println("Unsupported resource type: " + resourceType);
        return 2;
    }
}

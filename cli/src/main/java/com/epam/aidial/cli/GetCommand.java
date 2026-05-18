package com.epam.aidial.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "get", description = "Read entities (kubectl-style alias for <type> list).", mixinStandardHelpOptions = true)
public class GetCommand implements Callable<Integer> {

    @ParentCommand
    DialCli parent;
    @Spec
    CommandSpec spec;
    @Parameters(arity = "1", description = "Resource type (e.g. models, roles, keys).")
    String resourceType;

    private static final Set<String> LIST_TYPES = Set.of(
            "models", "applications", "toolsets",
            "interceptors", "roles", "keys", "routes", "schemas"
    );
    private static final Set<String> SINGLETON_TYPES = Set.of("settings");

    @Override
    public Integer call() {
        if (LIST_TYPES.contains(resourceType)) {
            return EntityReader.listEntities(parent, spec, resourceType);
        }
        if (SINGLETON_TYPES.contains(resourceType)) {
            return EntityReader.readSingleton(parent, spec, resourceType);
        }
        spec.commandLine().getErr().println("Unsupported resource type: " + resourceType);
        return 2;
    }
}

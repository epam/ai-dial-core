package com.epam.aidial.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
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
    @Option(names = "--source", description = "Config source: API (default) or FILE.", defaultValue = "API")
    ConfigSource source;

    private static final Set<String> LIST_TYPES = Set.of(
            "models", "applications", "toolsets",
            "interceptors", "roles", "keys", "routes", "schemas"
    );
    private static final Set<String> SINGLETON_TYPES = Set.of("settings");

    @Override
    public Integer call() {
        if (LIST_TYPES.contains(resourceType)) {
            return source == ConfigSource.FILE
                    ? EntityReader.listConfigFileEntities(parent, spec, resourceType)
                    : EntityReader.listEntities(parent, spec, resourceType);
        }
        if (SINGLETON_TYPES.contains(resourceType)) {
            return source == ConfigSource.FILE
                    ? EntityReader.readConfigFileSingleton(parent, spec)
                    : EntityReader.readSingleton(parent, spec, resourceType);
        }
        spec.commandLine().getErr().println("Unsupported resource type: " + resourceType);
        return 2;
    }
}

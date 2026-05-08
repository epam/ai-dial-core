package com.epam.aidial.cli;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import io.quarkus.runtime.Quarkus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

import java.nio.file.Path;

@TopCommand
@Command(
        name = "dial-cli",
        mixinStandardHelpOptions = true,
        version = "dial-cli 0.0.0",
        subcommands = {
                EnvCommand.class,
                GetCommand.class,
                ModelCommand.class,
                ApplicationCommand.class,
                ToolsetCommand.class,
                InterceptorCommand.class,
                RoleCommand.class,
                KeyCommand.class,
                RouteCommand.class,
                SchemaCommand.class,
                SettingsCommand.class,
                ExportCommand.class,
                DiffCommand.class,
                ApplyCommand.class,
                CompletionCommand.class
        }
)
public class DialCli {

    // Global flags use ScopeType.INHERIT so they can appear at any depth — `dial-cli model get foo
    // -o yaml` works the same as `dial-cli -o yaml model get foo`. Without INHERIT Picocli only
    // parses the option when it appears before the subcommand chain.
    @Option(names = {"-e", "--env"}, scope = ScopeType.INHERIT,
            description = "Target environment (overrides defaults.env in profile).")
    String env;

    @Option(names = "--config", scope = ScopeType.INHERIT,
            description = "CLI config file (default: ~/.dial-cli/config.yaml).")
    Path configPath;

    @Option(names = "--api-url", scope = ScopeType.INHERIT, description = "Override API URL.")
    String apiUrl;

    @Option(names = "--api-key-file", scope = ScopeType.INHERIT,
            description = "Read API key from file (CI secret mounts, SOPS-decrypted files).")
    Path apiKeyFile;

    @Option(names = {"-o", "--output"}, scope = ScopeType.INHERIT, defaultValue = "table",
            description = "Output format: table (default), json, yaml.")
    String output;

    @Option(names = {"-v", "--verbose"}, scope = ScopeType.INHERIT, description = "Verbose output.")
    boolean verbose;

    @Option(names = "--dry-run", scope = ScopeType.INHERIT,
            description = "Preview changes without applying.")
    boolean dryRun;

    public static void main(String[] args) {
        Quarkus.run(args);
    }
}

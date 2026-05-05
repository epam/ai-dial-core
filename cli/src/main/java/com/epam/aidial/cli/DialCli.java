package com.epam.aidial.cli;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import io.quarkus.runtime.Quarkus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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

    @Option(names = {"-e", "--env"}, description = "Target environment (overrides defaults.env in profile).")
    String env;

    @Option(names = "--config", description = "CLI config file (default: ~/.dial-cli/config.yaml).")
    Path configPath;

    @Option(names = "--api-url", description = "Override API URL.")
    String apiUrl;

    @Option(names = "--api-key-file", description = "Read API key from file (CI secret mounts, SOPS-decrypted files).")
    Path apiKeyFile;

    @Option(names = {"-o", "--output"}, description = "Output format: table (default), json, yaml.", defaultValue = "table")
    String output;

    @Option(names = {"-v", "--verbose"}, description = "Verbose output.")
    boolean verbose;

    @Option(names = "--dry-run", description = "Preview changes without applying.")
    boolean dryRun;

    public static void main(String[] args) {
        Quarkus.run(args);
    }
}

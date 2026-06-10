package com.epam.aidial.cli;

import com.epam.aidial.cli.command.ApplicationCommand;
import com.epam.aidial.cli.command.ApplyCommand;
import com.epam.aidial.cli.command.CompletionCommand;
import com.epam.aidial.cli.command.EnvCommand;
import com.epam.aidial.cli.command.GetCommand;
import com.epam.aidial.cli.command.InterceptorCommand;
import com.epam.aidial.cli.command.KeyCommand;
import com.epam.aidial.cli.command.ModelCommand;
import com.epam.aidial.cli.command.RoleCommand;
import com.epam.aidial.cli.command.RouteCommand;
import com.epam.aidial.cli.command.SchemaCommand;
import com.epam.aidial.cli.command.SettingsCommand;
import com.epam.aidial.cli.command.ToolsetCommand;
import com.epam.aidial.cli.service.CliOptionsDto;
import com.epam.aidial.cli.service.OutputFormatDto;
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
    public String env;

    @Option(names = "--config", scope = ScopeType.INHERIT,
            description = "CLI config file (default: ~/.dial-cli/config.yaml).")
    public Path configPath;

    @Option(names = "--api-url", scope = ScopeType.INHERIT, description = "Override API URL.")
    public String apiUrl;

    @Option(names = "--api-key-file", scope = ScopeType.INHERIT,
            description = "Read API key from file (CI secret mounts, SOPS-decrypted files).")
    public Path apiKeyFile;

    @Option(names = {"-o", "--output"}, scope = ScopeType.INHERIT,
            description = "Output format: ${COMPLETION-CANDIDATES} (default: table).")
    public OutputFormatDto output;

    @Option(names = "--dry-run", scope = ScopeType.INHERIT,
            description = "Preview changes without applying.")
    public boolean dryRun;

    public CliOptionsDto toCliOptionsDto() {
        return new CliOptionsDto(env, configPath, apiUrl, apiKeyFile, output, dryRun);
    }

    public static void main(String[] args) {
        Quarkus.run(args);
    }
}

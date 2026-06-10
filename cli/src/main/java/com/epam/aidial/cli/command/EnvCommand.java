package com.epam.aidial.cli.command;

import com.epam.aidial.cli.DialCli;
import com.epam.aidial.cli.data.CliProfile;
import com.epam.aidial.cli.data.Environment;
import com.epam.aidial.cli.data.ProfileLoader;
import com.epam.aidial.cli.service.EnvResolver;
import com.epam.aidial.cli.service.auth.ApiKeyResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

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

    @ParentCommand
    DialCli parent;

    @Command(name = "list", description = "List configured environments.")
    static class List implements Callable<Integer> {
        @ParentCommand
        EnvCommand env;
        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            DialCli root = env.parent;
            CliProfile profile = ProfileLoader.load(root.configPath);
            PrintWriter out = spec.commandLine().getOut();

            Map<String, Environment> environments = profile.getEnvironments();
            if (environments == null || environments.isEmpty()) {
                out.println("No environments configured.");
                return 0;
            }
            String current = EnvResolver.resolveDefault(profile);
            for (String name : new TreeMap<>(environments).keySet()) {
                String marker = name.equals(current) ? "* " : "  ";
                out.println(marker + name);
            }
            return 0;
        }
    }

    @Command(name = "current", description = "Print the currently selected environment.")
    static class Current implements Callable<Integer> {
        @ParentCommand
        EnvCommand env;
        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            DialCli root = env.parent;
            CliProfile profile = ProfileLoader.load(root.configPath);
            String current = EnvResolver.resolveDefault(profile);
            if (current == null) {
                spec.commandLine().getErr().println("No environment selected.");
                return 2;
            }
            spec.commandLine().getOut().println(current);
            return 0;
        }
    }

    @Command(name = "use", description = "Persist defaults.env in the CLI profile.")
    static class Use implements Callable<Integer> {
        @ParentCommand
        EnvCommand env;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Environment name to make default.")
        String name;

        @Override
        public Integer call() {
            DialCli root = env.parent;
            CliProfile profile = ProfileLoader.load(root.configPath);
            Map<String, Environment> environments = profile.getEnvironments();
            if (environments == null || environments.isEmpty() || !environments.containsKey(name)) {
                PrintWriter err = spec.commandLine().getErr();
                err.println("Environment '" + name + "' not found in profile.");
                if (environments != null && !environments.isEmpty()) {
                    err.println("Available environments: " + String.join(", ", new TreeMap<>(environments).keySet()));
                }
                return 2;
            }
            ProfileLoader.saveDefaultEnv(root.configPath, name);
            spec.commandLine().getOut().println("Switched to environment '" + name + "'.");
            return 0;
        }
    }

    @Command(name = "check", description = "Probe API URL + credential resolution for a profile.")
    public static class Check implements Callable<Integer> {
        @ParentCommand
        EnvCommand env;
        @Spec
        CommandSpec spec;

        public ApiKeyResolver apiKeyResolver = new ApiKeyResolver();

        @Override
        public Integer call() {
            DialCli root = env.parent;
            CliProfile profile = ProfileLoader.load(root.configPath);
            String name = EnvResolver.resolveCurrent(root.toCliOptionsDto(), profile);
            if (name == null) {
                spec.commandLine().getErr().println("No environment selected.");
                return 2;
            }

            Environment target = EnvResolver.lookupEnv(profile, name);
            String apiUrl = target.getApiUrl();
            if (apiUrl == null || apiUrl.isBlank()) {
                spec.commandLine().getErr().println("Environment '" + name + "' has no api_url configured.");
                return 2;
            }
            PrintWriter out = spec.commandLine().getOut();
            out.println("Environment: " + name);
            out.println("API URL:     " + apiUrl);
            out.println("Credentials: " + apiKeyResolver.describeSource(target));
            return 0;
        }
    }
}

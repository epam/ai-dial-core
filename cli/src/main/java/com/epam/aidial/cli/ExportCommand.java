package com.epam.aidial.cli;

import com.epam.aidial.cli.http.CliHttpClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "export",
        description = "Export the full DIAL configuration snapshot.",
        mixinStandardHelpOptions = true
)
public class ExportCommand implements Callable<Integer> {

    @ParentCommand
    DialCli parent;
    @Spec
    CommandSpec spec;

    @Option(names = {"-f", "--output-file"}, description = "Write export to file (default: stdout).")
    Path outputFile;

    @Override
    public Integer call() {
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(parent, spec);
        if (resolved == null) {
            return 2;
        }
        String accept = "yaml".equals(parent.output) ? "application/yaml" : "application/json";
        CliHttpClient.Response resp;
        try {
            resp = new CliHttpClient(resolved.apiUrl(), resolved.apiKey()).get("/v1/admin/export", accept);
        } catch (CliHttpClient.NetworkException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }
        if (resp.status() >= 300) {
            spec.commandLine().getErr().println("HTTP " + resp.status() + " " + resp.body());
            return CliHttpClient.toExitCode(resp.status());
        }
        if (outputFile != null) {
            if (Files.isDirectory(outputFile)) {
                spec.commandLine().getErr().println("--output-file path is a directory: " + outputFile);
                return 1;
            }
            try {
                Path parentDir = outputFile.toAbsolutePath().getParent();
                if (parentDir != null) {
                    Files.createDirectories(parentDir);
                }
                Files.writeString(outputFile, resp.body());
            } catch (IOException e) {
                spec.commandLine().getErr().println("Failed to write export to " + outputFile + ": " + e.getMessage());
                return 1;
            }
        } else {
            spec.commandLine().getOut().print(resp.body());
        }
        return 0;
    }
}

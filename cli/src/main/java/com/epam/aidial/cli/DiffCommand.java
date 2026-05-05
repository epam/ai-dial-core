package com.epam.aidial.cli;

import com.epam.aidial.cli.http.CliHttpClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "diff",
        description = "Structural diff of full configuration between two environments.",
        mixinStandardHelpOptions = true
)
public class DiffCommand implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ParentCommand
    DialCli parent;
    @Spec
    CommandSpec spec;

    @Option(names = "--source", required = true, description = "Source environment.")
    String sourceEnv;
    @Option(names = "--target", required = true, description = "Target environment.")
    String targetEnv;

    @Override
    public Integer call() {
        EntityReader.ResolvedEnv source = EntityReader.resolveEnv(parent, spec, sourceEnv);
        if (source == null) {
            return 2;
        }
        EntityReader.ResolvedEnv target = EntityReader.resolveEnv(parent, spec, targetEnv);
        if (target == null) {
            return 2;
        }

        Fetched sourceResult = fetchExport(source);
        if (sourceResult.tree() == null) {
            return sourceResult.exitCode();
        }
        Fetched targetResult = fetchExport(target);
        if (targetResult.tree() == null) {
            return targetResult.exitCode();
        }

        List<JsonDiff.Change> changes = JsonDiff.diff(sourceResult.tree(), targetResult.tree());
        if (changes.isEmpty()) {
            spec.commandLine().getOut().println("No differences.");
            return 0;
        }
        for (JsonDiff.Change c : changes) {
            spec.commandLine().getOut().println(c);
        }
        return 0;
    }

    private Fetched fetchExport(EntityReader.ResolvedEnv env) {
        CliHttpClient.Response resp;
        try {
            resp = new CliHttpClient(env.apiUrl(), env.apiKey()).get("/v1/admin/export");
        } catch (CliHttpClient.NetworkException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return new Fetched(1, null);
        }
        if (resp.status() >= 300) {
            spec.commandLine().getErr().println("HTTP " + resp.status() + " " + resp.body());
            return new Fetched(CliHttpClient.toExitCode(resp.status()), null);
        }
        try {
            return new Fetched(0, JSON.readTree(resp.body()));
        } catch (JsonProcessingException e) {
            spec.commandLine().getErr().println("Failed to parse export from " + env.envName() + ": " + e.getMessage());
            return new Fetched(1, null);
        }
    }

    private record Fetched(int exitCode, JsonNode tree) { }
}

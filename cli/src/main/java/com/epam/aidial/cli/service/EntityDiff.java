package com.epam.aidial.cli.service;

import com.epam.aidial.cli.exception.CliException;
import com.epam.aidial.cli.service.json.JsonDiff;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Model.CommandSpec;

import java.util.List;

public final class EntityDiff {

    private static final ObjectMapper JSON = new ObjectMapper();

    private EntityDiff() {
    }

    public static void run(CliOptionsDto opts, CommandSpec spec, String type, String sourceEnv, String targetEnv, String name) {
        EnvResolver.ResolvedEnv source = EnvResolver.resolveEnv(opts, sourceEnv);
        EnvResolver.ResolvedEnv target = EnvResolver.resolveEnv(opts, targetEnv);

        JsonNode sourceTree = fetchOrAbsent(source, type, name);
        JsonNode targetTree = fetchOrAbsent(target, type, name);

        printChanges(spec, sourceTree, targetTree);
    }

    public static void runSingleton(CliOptionsDto opts, CommandSpec spec, String type, String sourceEnv, String targetEnv, String name) {
        EnvResolver.ResolvedEnv source = EnvResolver.resolveEnv(opts, sourceEnv);
        EnvResolver.ResolvedEnv target = EnvResolver.resolveEnv(opts, targetEnv);

        JsonNode sourceTree = fetchSingletonOrAbsent(source, type, name);
        JsonNode targetTree = fetchSingletonOrAbsent(target, type, name);

        printChanges(spec, sourceTree, targetTree);
    }

    private static JsonNode fetchOrAbsent(EnvResolver.ResolvedEnv env, String type, String id) {
        try {
            return EntityReader.getEntity(env, type, id);
        } catch (JsonProcessingException e) {
            throw CliException.jsonProcessing(env.envName() + ": failed to parse response: " + e.getMessage());
        } catch (CliException e) {
            if (e.exitCode() == 4) {
                return JSON.createObjectNode();
            }
            throw new CliException(env.envName() + ": " + e.getMessage(), e.exitCode());
        }
    }

    private static JsonNode fetchSingletonOrAbsent(EnvResolver.ResolvedEnv env, String type, String id) {
        try {
            JsonNode blobSingleton = EntityReader.getBlobSingleton(env, type, id);
            JsonNode configFileSingleton = EntityReader.getConfigFileSingleton(env, type, id);

            ObjectNode merged = JSON.createObjectNode();
            if (!blobSingleton.isEmpty()) {
                merged.set("api", blobSingleton);
            }
            if (!configFileSingleton.isEmpty()) {
                merged.set("file", configFileSingleton);
            }
            return merged;
        } catch (JsonProcessingException e) {
            throw CliException.jsonProcessing(env.envName() + ": failed to parse response: " + e.getMessage());
        }
    }

    private static void printChanges(CommandSpec spec, JsonNode sourceTree, JsonNode targetTree) {
        List<JsonDiff.Change> changes = JsonDiff.diff(sourceTree, targetTree);
        if (changes.isEmpty()) {
            spec.commandLine().getOut().println("No differences.");
        }
        for (JsonDiff.Change c : changes) {
            spec.commandLine().getOut().println(c);
        }
    }
}

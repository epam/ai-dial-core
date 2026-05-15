package com.epam.aidial.cli;

import com.epam.aidial.cli.http.CliHttpClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Model.CommandSpec;

public final class EntityDiff {

    private static final ObjectMapper JSON = new ObjectMapper();

    private EntityDiff() {
    }

    static int run(DialCli root, CommandSpec spec, String type, String bucket, String canonicalPrefix,
                   String sourceEnv, String targetEnv, String name) {
        EnvResolver.ResolvedEnv source = EnvResolver.resolveEnv(root, spec, sourceEnv);
        if (source == null) {
            return 2;
        }
        EnvResolver.ResolvedEnv target = EnvResolver.resolveEnv(root, spec, targetEnv);
        if (target == null) {
            return 2;
        }
        String path;
        boolean isList;
        if (name == null || name.isBlank()) {
            path = "/v1/" + type + "/" + bucket + "/?limit=100";
            isList = true;
        } else {
            if (!name.startsWith(canonicalPrefix) || name.length() == canonicalPrefix.length()
                    || name.indexOf('/', canonicalPrefix.length()) >= 0) {
                spec.commandLine().getErr().println(
                        "--name must be a canonical id '" + canonicalPrefix + "<name>'; got '" + name + "'.");
                return 2;
            }
            path = "/v1/" + name;
            isList = false;
        }
        JsonNode sourceTree = fetchOrAbsent(spec, source, path, isList);
        if (sourceTree == null) {
            return 1;
        }
        JsonNode targetTree = fetchOrAbsent(spec, target, path, isList);
        if (targetTree == null) {
            return 1;
        }
        return printChanges(spec, sourceTree, targetTree);
    }

    static int runSingleton(DialCli root, CommandSpec spec, String singletonPath, String sourceEnv, String targetEnv) {
        EnvResolver.ResolvedEnv source = EnvResolver.resolveEnv(root, spec, sourceEnv);
        if (source == null) {
            return 2;
        }
        EnvResolver.ResolvedEnv target = EnvResolver.resolveEnv(root, spec, targetEnv);
        if (target == null) {
            return 2;
        }
        JsonNode sourceTree = fetchOrAbsent(spec, source, singletonPath, false);
        if (sourceTree == null) {
            return 1;
        }
        JsonNode targetTree = fetchOrAbsent(spec, target, singletonPath, false);
        if (targetTree == null) {
            return 1;
        }
        return printChanges(spec, sourceTree, targetTree);
    }

    private static int printChanges(CommandSpec spec, JsonNode sourceTree, JsonNode targetTree) {
        java.util.List<JsonDiff.Change> changes = JsonDiff.diff(sourceTree, targetTree);
        if (changes.isEmpty()) {
            spec.commandLine().getOut().println("No differences.");
            return 0;
        }
        for (JsonDiff.Change c : changes) {
            spec.commandLine().getOut().println(c);
        }
        return 0;
    }

    private static JsonNode fetchOrAbsent(CommandSpec spec, EnvResolver.ResolvedEnv env, String path,
                                          boolean isList) {
        CliHttpClient.Response resp;
        try {
            resp = new CliHttpClient(env.apiUrl(), env.apiKey()).get(path);
        } catch (CliHttpClient.NetworkException e) {
            spec.commandLine().getErr().println(env.envName() + ": " + e.getMessage());
            return null;
        }
        if (resp.status() == 404 && !isList) {
            return JSON.createObjectNode();
        }
        if (resp.status() >= 300) {
            spec.commandLine().getErr().println(env.envName() + ": HTTP " + resp.status() + " " + resp.body());
            return null;
        }
        try {
            JsonNode body = JSON.readTree(resp.body());
            if (!isList) {
                return body;
            }
            JsonNode items = body.get("items");
            if (items == null || !items.isArray()) {
                spec.commandLine().getErr().println(env.envName() + ": unexpected listing shape (missing 'items').");
                return null;
            }
            JsonNode hasMore = body.get("hasMore");
            if (hasMore != null && hasMore.asBoolean()) {
                spec.commandLine().getErr().println("[warn] " + env.envName() + ": result truncated at 100 items.");
            }
            ObjectNode keyed = JSON.createObjectNode();
            for (JsonNode item : items) {
                JsonNode itemName = item.get("name");
                if (itemName == null || itemName.isNull()) {
                    continue;
                }
                keyed.set(itemName.asText(), item);
            }
            return keyed;
        } catch (JsonProcessingException e) {
            spec.commandLine().getErr().println(env.envName() + ": failed to parse response: " + e.getMessage());
            return null;
        }
    }
}

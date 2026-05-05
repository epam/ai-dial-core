package com.epam.aidial.cli;

import com.epam.aidial.cli.auth.ApiKeyResolver;
import com.epam.aidial.cli.auth.CliAuthException;
import com.epam.aidial.cli.config.CliConfigException;
import com.epam.aidial.cli.config.CliProfile;
import com.epam.aidial.cli.config.Environment;
import com.epam.aidial.cli.config.ProfileLoader;
import com.epam.aidial.cli.http.CliHttpClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "model",
        description = "Read DIAL model entities.",
        mixinStandardHelpOptions = true,
        subcommands = {ModelCommand.Get.class, ModelCommand.List.class}
)
public class ModelCommand {

    @ParentCommand
    DialCli parent;

    static final ObjectMapper JSON = new ObjectMapper();
    static final YAMLMapper YAML = new YAMLMapper();
    static final String[] MODEL_TABLE_HEADERS = {"NAME", "ENDPOINT"};

    @Command(name = "get", description = "Get a single model by name (or canonical id).")
    static class Get implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Model name or canonical id (models/<bucket>/<name>).")
        String name;

        @Override
        public Integer call() {
            return readEntity(model.parent, spec, "models", name);
        }
    }

    @Command(name = "list", description = "List models in the public bucket.")
    static class List implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            return listEntities(model.parent, spec, "models");
        }
    }

    static int readEntity(DialCli root, CommandSpec spec, String type, String identifier) {
        ResolvedEnv resolved = resolveEnv(root, spec);
        if (resolved == null) {
            return 2;
        }
        String path;
        try {
            path = identifierToPath(type, identifier);
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        }
        CliHttpClient.Response resp;
        try {
            resp = new CliHttpClient(resolved.apiUrl(), resolved.apiKey()).get(path);
        } catch (CliHttpClient.NetworkException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }
        if (resp.status() >= 300) {
            spec.commandLine().getErr().println("HTTP " + resp.status() + " " + resp.body());
            return CliHttpClient.toExitCode(resp.status());
        }
        try {
            JsonNode node = JSON.readTree(resp.body());
            spec.commandLine().getOut().println(renderSingle(node, root.output));
        } catch (JsonProcessingException e) {
            spec.commandLine().getErr().println("Failed to parse response: " + e.getMessage());
            return 1;
        }
        return 0;
    }

    static int listEntities(DialCli root, CommandSpec spec, String type) {
        ResolvedEnv resolved = resolveEnv(root, spec);
        if (resolved == null) {
            return 2;
        }
        CliHttpClient.Response resp;
        try {
            resp = new CliHttpClient(resolved.apiUrl(), resolved.apiKey()).get("/v1/" + type + "/public/?limit=100");
        } catch (CliHttpClient.NetworkException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }
        if (resp.status() >= 300) {
            spec.commandLine().getErr().println("HTTP " + resp.status() + " " + resp.body());
            return CliHttpClient.toExitCode(resp.status());
        }
        try {
            JsonNode node = JSON.readTree(resp.body());
            JsonNode items = node.get("items");
            if (items == null || !items.isArray()) {
                spec.commandLine().getErr().println("Unexpected listing response shape: missing 'items' array.");
                return 1;
            }
            spec.commandLine().getOut().println(renderList(items, root.output));
        } catch (JsonProcessingException e) {
            spec.commandLine().getErr().println("Failed to parse response: " + e.getMessage());
            return 1;
        }
        return 0;
    }

    static String renderSingle(JsonNode node, String fmt) throws JsonProcessingException {
        return switch (fmt) {
            case "json" -> JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
            case "yaml" -> YAML.writeValueAsString(node).stripTrailing();
            case "table" -> renderTable(java.util.List.of(node));
            default -> JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        };
    }

    static String renderList(JsonNode items, String fmt) throws JsonProcessingException {
        return switch (fmt) {
            case "json" -> JSON.writerWithDefaultPrettyPrinter().writeValueAsString(items);
            case "yaml" -> YAML.writeValueAsString(items).stripTrailing();
            case "table" -> {
                java.util.ArrayList<JsonNode> rows = new ArrayList<>();
                items.forEach(rows::add);
                yield renderTable(rows);
            }
            default -> JSON.writerWithDefaultPrettyPrinter().writeValueAsString(items);
        };
    }

    static String renderTable(java.util.List<JsonNode> rows) {
        int[] widths = new int[MODEL_TABLE_HEADERS.length];
        for (int i = 0; i < MODEL_TABLE_HEADERS.length; i++) {
            widths[i] = MODEL_TABLE_HEADERS[i].length();
        }
        java.util.List<String[]> values = new ArrayList<>();
        for (JsonNode r : rows) {
            String[] row = {textOrEmpty(r, "name"), textOrEmpty(r, "endpoint")};
            values.add(row);
            for (int i = 0; i < row.length; i++) {
                if (row[i].length() > widths[i]) {
                    widths[i] = row[i].length();
                }
            }
        }
        StringBuilder out = new StringBuilder();
        appendRow(out, MODEL_TABLE_HEADERS, widths);
        for (String[] row : values) {
            appendRow(out, row, widths);
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private static void appendRow(StringBuilder out, String[] cells, int[] widths) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append("  ");
            }
            if (i < cells.length - 1) {
                out.append(String.format("%-" + widths[i] + "s", cells[i]));
            } else {
                out.append(cells[i]);
            }
        }
        out.append('\n');
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? "" : v.asText();
    }

    private static String identifierToPath(String type, String identifier) {
        if (identifier.startsWith(type + "/")) {
            return "/v1/" + identifier;
        }
        if (identifier.contains("/")) {
            throw new IllegalArgumentException(
                    "Ambiguous identifier '" + identifier
                            + "'. Use a plain name or full canonical id '" + type + "/public/<name>'.");
        }
        return "/v1/" + type + "/public/" + URLEncoder.encode(identifier, StandardCharsets.UTF_8);
    }

    static ResolvedEnv resolveEnv(DialCli root, CommandSpec spec) {
        CliProfile profile;
        try {
            profile = ProfileLoader.load(root.configPath);
        } catch (CliConfigException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return null;
        }
        String envName = root.env;
        if (envName == null || envName.isBlank()) {
            envName = (profile.getDefaults() != null) ? profile.getDefaults().getEnv() : null;
        }
        if (envName == null || envName.isBlank()) {
            spec.commandLine().getErr().println(
                    "No environment selected. Pass --env or set defaults.env via 'dial-cli env use'.");
            return null;
        }
        Map<String, Environment> envs = profile.getEnvironments();
        Environment env = (envs != null) ? envs.get(envName) : null;
        if (env == null) {
            spec.commandLine().getErr().println("Environment '" + envName + "' not found in profile.");
            return null;
        }
        String apiUrl = (root.apiUrl != null && !root.apiUrl.isBlank()) ? root.apiUrl : env.getApiUrl();
        if (apiUrl == null || apiUrl.isBlank()) {
            spec.commandLine().getErr().println(
                    "Environment '" + envName + "' has no api_url and no --api-url override.");
            return null;
        }
        if (apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }
        try {
            String apiKey = new ApiKeyResolver().resolve(envName, env, root.apiKeyFile);
            return new ResolvedEnv(envName, apiUrl, apiKey);
        } catch (CliAuthException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return null;
        }
    }

    record ResolvedEnv(String envName, String apiUrl, String apiKey) { }
}

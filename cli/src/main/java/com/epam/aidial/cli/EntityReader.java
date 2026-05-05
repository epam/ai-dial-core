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
import picocli.CommandLine.Model.CommandSpec;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class EntityReader {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();

    private static final Map<String, String> TYPE_DEFAULT_BUCKET = Map.ofEntries(
            Map.entry("models", "public"),
            Map.entry("applications", "public"),
            Map.entry("toolsets", "public"),
            Map.entry("interceptors", "platform"),
            Map.entry("roles", "platform"),
            Map.entry("keys", "platform"),
            Map.entry("routes", "platform"),
            Map.entry("schemas", "public"),
            Map.entry("settings", "platform")
    );

    private static final TableShape DEFAULT_SHAPE = new TableShape(new String[]{"NAME"}, new String[]{"name"});

    private static final Map<String, TableShape> TYPE_TABLE_SHAPE = Map.of(
            "models", new TableShape(new String[]{"NAME", "ENDPOINT"}, new String[]{"name", "endpoint"})
    );

    private static final String SETTINGS_SINGLETON_NAME = "global";

    private EntityReader() {
    }

    public static int readEntity(DialCli root, CommandSpec spec, String type, String identifier) {
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
        return doGet(root, spec, resolved, path, false, type);
    }

    public static int readSingleton(DialCli root, CommandSpec spec, String type) {
        ResolvedEnv resolved = resolveEnv(root, spec);
        if (resolved == null) {
            return 2;
        }
        String bucket = TYPE_DEFAULT_BUCKET.get(type);
        if (bucket == null) {
            spec.commandLine().getErr().println("Unsupported entity type: " + type);
            return 2;
        }
        String path = "/v1/" + type + "/" + bucket + "/" + SETTINGS_SINGLETON_NAME;
        return doGet(root, spec, resolved, path, false, type);
    }

    public static int listEntities(DialCli root, CommandSpec spec, String type) {
        ResolvedEnv resolved = resolveEnv(root, spec);
        if (resolved == null) {
            return 2;
        }
        String bucket = TYPE_DEFAULT_BUCKET.get(type);
        if (bucket == null) {
            spec.commandLine().getErr().println("Unsupported entity type: " + type);
            return 2;
        }
        String path = "/v1/" + type + "/" + bucket + "/?limit=100";
        return doGet(root, spec, resolved, path, true, type);
    }

    private static int doGet(DialCli root, CommandSpec spec, ResolvedEnv resolved, String path, boolean isList, String type) {
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
            if (isList) {
                JsonNode items = node.get("items");
                if (items == null || !items.isArray()) {
                    spec.commandLine().getErr().println("Unexpected listing response shape: missing 'items' array.");
                    return 1;
                }
                JsonNode hasMore = node.get("hasMore");
                if (hasMore != null && hasMore.asBoolean()) {
                    spec.commandLine().getErr().println("[warn] Result truncated at 100 items.");
                }
                spec.commandLine().getOut().println(renderList(items, root.output, type));
            } else {
                spec.commandLine().getOut().println(renderSingle(node, root.output, type));
            }
        } catch (JsonProcessingException e) {
            spec.commandLine().getErr().println("Failed to parse response: " + e.getMessage());
            return 1;
        }
        return 0;
    }

    static String renderSingle(JsonNode node, String fmt, String type) throws JsonProcessingException {
        return switch (fmt) {
            case "json" -> JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
            case "yaml" -> YAML.writeValueAsString(node).stripTrailing();
            case "table" -> renderTable(List.of(node), type);
            default -> JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        };
    }

    static String renderList(JsonNode items, String fmt, String type) throws JsonProcessingException {
        return switch (fmt) {
            case "json" -> JSON.writerWithDefaultPrettyPrinter().writeValueAsString(items);
            case "yaml" -> YAML.writeValueAsString(items).stripTrailing();
            case "table" -> {
                List<JsonNode> rows = new ArrayList<>();
                items.forEach(rows::add);
                yield renderTable(rows, type);
            }
            default -> JSON.writerWithDefaultPrettyPrinter().writeValueAsString(items);
        };
    }

    static String renderTable(List<JsonNode> rows, String type) {
        TableShape shape = TYPE_TABLE_SHAPE.getOrDefault(type, DEFAULT_SHAPE);
        String[] headers = shape.headers();
        String[] fields = shape.fields();
        int[] widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            widths[i] = headers[i].length();
        }
        List<String[]> values = new ArrayList<>();
        for (JsonNode r : rows) {
            String[] row = new String[fields.length];
            for (int i = 0; i < fields.length; i++) {
                row[i] = textOrEmpty(r, fields[i]);
            }
            values.add(row);
            for (int i = 0; i < row.length; i++) {
                if (row[i].length() > widths[i]) {
                    widths[i] = row[i].length();
                }
            }
        }
        StringBuilder out = new StringBuilder();
        appendRow(out, headers, widths);
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
        String bucket = TYPE_DEFAULT_BUCKET.get(type);
        if (bucket == null) {
            throw new IllegalArgumentException("Unsupported entity type: " + type);
        }
        if (identifier.contains("/")) {
            throw new IllegalArgumentException(
                    "Ambiguous identifier '" + identifier
                            + "'. Use a plain name or full canonical id '" + type + "/" + bucket + "/<name>'.");
        }
        return "/v1/" + type + "/" + bucket + "/" + URLEncoder.encode(identifier, StandardCharsets.UTF_8);
    }

    static ResolvedEnv resolveEnv(DialCli root, CommandSpec spec) {
        return resolveEnv(root, spec, null);
    }

    static ResolvedEnv resolveEnv(DialCli root, CommandSpec spec, String explicitEnv) {
        CliProfile profile;
        try {
            profile = ProfileLoader.load(root.configPath);
        } catch (CliConfigException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return null;
        }
        String envName = (explicitEnv != null && !explicitEnv.isBlank()) ? explicitEnv : root.env;
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
        boolean useApiUrlOverride = explicitEnv == null && root.apiUrl != null && !root.apiUrl.isBlank();
        String apiUrl = useApiUrlOverride ? root.apiUrl : env.getApiUrl();
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

    private record TableShape(String[] headers, String[] fields) { }

    record ResolvedEnv(String envName, String apiUrl, String apiKey) { }
}

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

    private static final TableShape DEFAULT_SHAPE = new TableShape(
            new String[]{"NAME", "SOURCE", "STATUS"},
            new String[]{"name", "source", "status"});

    private static final Map<String, TableShape> TYPE_TABLE_SHAPE = Map.of(
            "models", new TableShape(
                    new String[]{"NAME", "SOURCE", "STATUS", "ENDPOINT"},
                    new String[]{"name", "source", "status", "endpoint"})
    );

    private static final String SETTINGS_SINGLETON_NAME = "global";

    private EntityReader() {
    }

    public static int readEntity(DialCli root, CommandSpec spec, String type, String identifier) {
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(root, spec);
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
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(root, spec);
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
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(root, spec);
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

    private static int doGet(DialCli root, CommandSpec spec, EnvResolver.ResolvedEnv resolved, String path, boolean isList, String type) {
        CliHttpClient.Response resp;
        try {
            resp = new CliHttpClient(resolved.apiUrl(), resolved.apiKey()).get(path);
        } catch (CliHttpClient.NetworkException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }
        if (resp.status() >= 300) {
            spec.commandLine().getErr().println(formatHttpError(resp.status(), resp.body(), path));
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


    /**
     * Translate a non-2xx HTTP status into an operator-friendly stderr line. Wraps the four
     * common per-entity codes (404 / 409 / 412 / generic) with a recognizable verb, then echoes
     * the canonical-style identifier extracted from the request path so the message reads
     * standalone without needing the URL on screen.
     */
    static String formatHttpError(int status, String body, String requestPath) {
        String identifier = friendlyIdentifier(requestPath);
        String trimmed = (body == null) ? "" : body.strip();
        return switch (status) {
            case 404 -> "Not found: " + identifier;
            case 409 -> "Already exists: " + identifier
                    + (trimmed.isEmpty() ? "" : " — " + trimmed);
            case 412 -> "Stale ETag: " + identifier
                    + (trimmed.isEmpty() ? "" : " — " + trimmed);
            default -> "HTTP " + status + (trimmed.isEmpty() ? "" : " " + trimmed);
        };
    }

    private static String friendlyIdentifier(String requestPath) {
        if (requestPath == null) {
            return "(unknown)";
        }
        String stripped = requestPath.startsWith("/v1/") ? requestPath.substring(4) : requestPath;
        int query = stripped.indexOf('?');
        if (query >= 0) {
            stripped = stripped.substring(0, query);
        }
        return stripped.isBlank() ? "(unknown)" : stripped;
    }

    private record TableShape(String[] headers, String[] fields) { }
}

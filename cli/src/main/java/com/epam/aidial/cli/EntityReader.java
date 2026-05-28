package com.epam.aidial.cli;

import com.epam.aidial.cli.http.CliHttpClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Model.CommandSpec;

import java.util.Map;

public final class EntityReader {

    private static final ObjectMapper JSON = new ObjectMapper();

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

    private static final String SETTINGS_SINGLETON_NAME = "global";

    /** Controls which renderer method and pagination field to use in {@link #doGet}. */
    private enum ListContext { NONE, METADATA, FILE_CONFIG }

    private EntityReader() {
    }

    public static int readEntity(DialCli root, CommandSpec spec, String type, String identifier) {
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(root);
        String path;
        try {
            path = identifierToPath(type, identifier);
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        }
        return doGet(root, spec, resolved, path, null, ListContext.NONE, type);
    }

    public static int readSingleton(DialCli root, CommandSpec spec, String type) {
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(root);
        String bucket = TYPE_DEFAULT_BUCKET.get(type);
        if (bucket == null) {
            spec.commandLine().getErr().println("Unsupported entity type: " + type);
            return 2;
        }
        String path = "/v1/" + type + "/" + bucket + "/" + SETTINGS_SINGLETON_NAME;
        return doGet(root, spec, resolved, path, null, ListContext.NONE, type);
    }

    public static int listEntities(DialCli root, CommandSpec spec, String type) {
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(root);
        String bucket = TYPE_DEFAULT_BUCKET.get(type);
        if (bucket == null) {
            spec.commandLine().getErr().println("Unsupported entity type: " + type);
            return 2;
        }
        // Listings moved to /v1/metadata/... (ResourceFolderMetadata shape) in U.0
        String path = "/v1/metadata/" + type + "/" + bucket + "/";
        return doGet(root, spec, resolved, path, "limit=100", ListContext.METADATA, type);
    }

    // ---- File-config surface (U.1: /v1/admin/config/file/*) ----

    public static int readConfigFileEntity(DialCli root, CommandSpec spec, String type, String identifier) {
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(root);
        String name;
        try {
            name = simpleNameFromIdentifier(type, identifier);
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        }
        String path = "/v1/admin/config/file/" + type + "/" + name;
        return doGet(root, spec, resolved, path, null, ListContext.NONE, type);
    }

    public static int listConfigFileEntities(DialCli root, CommandSpec spec, String type) {
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(root);
        String path = "/v1/admin/config/file/" + type;
        return doGet(root, spec, resolved, path, null, ListContext.FILE_CONFIG, type);
    }

    public static int readConfigFileSingleton(DialCli root, CommandSpec spec) {
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(root);
        String path = "/v1/admin/config/file/settings/global";
        return doGet(root, spec, resolved, path, null, ListContext.NONE, "settings");
    }

    // ---- Internal helpers ----

    private static int doGet(DialCli root, CommandSpec spec, EnvResolver.ResolvedEnv resolved,
                             String path, String query, ListContext listCtx, String type) {
        CliHttpClient.Response resp;
        try {
            resp = new CliHttpClient(resolved.apiUrl(), resolved.apiKey()).get(path, query);
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
            EntityRenderer renderer = EntityRenderer.of(OutputFormatResolver.resolve(root));
            if (listCtx != ListContext.NONE) {
                JsonNode items = node.get("items");
                if (items == null || !items.isArray()) {
                    spec.commandLine().getErr().println("Unexpected listing response shape: missing 'items' array.");
                    return 1;
                }
                JsonNode nextToken = node.get("nextToken");
                if (nextToken != null && !nextToken.isNull()) {
                    spec.commandLine().getErr().println("[warn] Result truncated at 100 items.");
                }
                String rendered = listCtx == ListContext.FILE_CONFIG
                        ? renderer.renderFileList(items, type)
                        : renderer.renderMetadataList(items, type);
                spec.commandLine().getOut().println(rendered);
            } else {
                spec.commandLine().getOut().println(renderer.renderSingle(node, type));
            }
        } catch (JsonProcessingException e) {
            spec.commandLine().getErr().println("Failed to parse response: " + e.getMessage());
            return 1;
        }
        return 0;
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
        return "/v1/" + type + "/" + bucket + "/" + identifier;
    }

    private static String simpleNameFromIdentifier(String type, String identifier) {
        if (!identifier.contains("/")) {
            return identifier;
        }
        // Accept canonical id (e.g. "models/public/gpt-4") — strip to simple name
        String prefix = type + "/";
        if (identifier.startsWith(prefix)) {
            String rest = identifier.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                return rest.substring(slash + 1);
            }
        }
        throw new IllegalArgumentException(
                "Ambiguous identifier '" + identifier + "'. Use a plain name for file-config lookup.");
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

}

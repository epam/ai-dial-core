package com.epam.aidial.cli;

import com.epam.aidial.cli.http.CliHttpClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private EntityReader() {
    }

    public static void readEntity(DialCli root, CommandSpec spec, String type, String identifier) {
        EnvResolver.ResolvedEnv env = EnvResolver.resolveEnv(root);
        String path = identifier.contains("/")
                ?  identifierToPath(type, identifier)
                : "/v1/admin/config/file/" + type + "/" + identifier;

        CliHttpClient.Response resp = doGet(env, path);

        if (resp.status() >= 300) {
            throw CliException.httpError(resp.status(), resp.body(), path);
        }

        try {
            JsonNode response = JSON.readTree(resp.body());
            EntityRenderer renderer = EntityRenderer.of(OutputFormatResolver.resolve(root));
            spec.commandLine().getOut().println(renderer.renderSingle(response, type));
        } catch (JsonProcessingException e) {
            throw CliException.jsonProcessing("Failed to parse response: " + e.getMessage());
        }
    }

    /**
     * List entities from both API-managed storage and file-config, merged into a single output
     * with a SOURCE column (values: "api" or "file").
     */
    public static void listEntities(DialCli root, CommandSpec spec, String type) {
        EnvResolver.ResolvedEnv env = EnvResolver.resolveEnv(root);

        CliHttpClient.Response apiResp = getBlobEntitiesResponse(env, type);
        CliHttpClient.Response fileResp = getConfigFileEntitiesResponse(env, type);

        try {
            JsonNode apiNode = JSON.readTree(apiResp.body());
            JsonNode fileNode = JSON.readTree(fileResp.body());

            ArrayNode entries = JSON.createArrayNode();

            JsonNode apiItems = apiNode.get("items");
            if (apiItems != null && apiItems.isArray()) {
                if (apiNode.path("nextToken").isValueNode()) {
                    spec.commandLine().getErr().println("[warn] API result truncated at 100 items.");
                }
                for (JsonNode item : apiItems) {
                    JsonNode url = item.get("url");
                    if (url != null && !url.isNull()) {
                        ObjectNode node = entries.addObject();
                        node.put("name", url.asText()).put("source", "api");
                    }
                }
            }

            JsonNode fileItems = fileNode.get("items");
            if (fileItems != null && fileItems.isArray()) {
                for (JsonNode item : fileItems) {
                    JsonNode name = item.get("name");
                    if (name != null && !name.isNull()) {
                        ObjectNode node = entries.addObject();
                        node.put("name", name.asText()).put("source", "file");
                    }
                }
            }

            EntityRenderer renderer = EntityRenderer.of(OutputFormatResolver.resolve(root));
            spec.commandLine().getOut().println(renderer.renderList(entries, type));
        } catch (JsonProcessingException e) {
            throw CliException.jsonProcessing("Failed to parse response: " + e.getMessage());
        }
    }

    private static CliHttpClient.Response getBlobEntitiesResponse(EnvResolver.ResolvedEnv env, String type) {
        String bucket = TYPE_DEFAULT_BUCKET.get(type);
        if (bucket == null) {
            throw CliException.validation("Unsupported entity type: " + type);
        }

        String path = "/v1/metadata/" + type + "/" + bucket + "/";
        CliHttpClient.Response response = doGet(env, path, "limit=100");

        if (response.status() >= 300) {
            throw CliException.httpError(response.status(), response.body(), path);
        }

        return response;
    }

    // ---- File-config surface (U.1: /v1/admin/config/file/*) ----
    private static CliHttpClient.Response getConfigFileEntitiesResponse(EnvResolver.ResolvedEnv env, String type) {
        String path = "/v1/admin/config/file/" + type;
        CliHttpClient.Response response = doGet(env, path);

        if (response.status() >= 300) {
            throw CliException.httpError(response.status(), response.body(), path);
        }

        return response;
    }

    public static void readSingleton(DialCli root, CommandSpec spec, String type, String name) {
        EnvResolver.ResolvedEnv env = EnvResolver.resolveEnv(root);

        CliHttpClient.Response apiResp = getBlobSingletonResponse(env, type, name);
        CliHttpClient.Response fileResp = getConfigFileSingletonResponse(env, type, name);

        try {
            JsonNode apiNode = JSON.readTree(apiResp.body());
            JsonNode fileNode = JSON.readTree(fileResp.body());

            ArrayNode entries = JSON.createArrayNode();

            if (!apiNode.isEmpty() && apiNode.isObject()) {
                ((ObjectNode) apiNode).put("source", "api");
                entries.add(apiNode);
            }

            if (!fileNode.isEmpty() && fileNode.isObject()) {
                ((ObjectNode) fileNode).put("source", "file");
                entries.add(fileNode);
            }

            EntityRenderer renderer = EntityRenderer.of(OutputFormatResolver.resolve(root));
            spec.commandLine().getOut().println(renderer.renderList(entries, type));
        } catch (JsonProcessingException e) {
            throw CliException.jsonProcessing("Failed to parse response: " + e.getMessage());
        }
    }

    private static CliHttpClient.Response getBlobSingletonResponse(EnvResolver.ResolvedEnv env, String type, String name) {
        String bucket = TYPE_DEFAULT_BUCKET.get(type);
        if (bucket == null) {
            throw CliException.validation("Unsupported entity type: " + type);
        }

        String path = "/v1/" + type + "/" + bucket + "/" + name;
        CliHttpClient.Response response = doGet(env, path);

        if (response.status() == 404) {
            return new CliHttpClient.Response(404, "", null);
        }

        if (response.status() >= 300) {
            throw CliException.httpError(response.status(), response.body(), path);
        }

        return response;
    }

    private static CliHttpClient.Response getConfigFileSingletonResponse(EnvResolver.ResolvedEnv env, String type, String name) {
        String path = "/v1/admin/config/file/" + type + "/" + name;
        CliHttpClient.Response response = doGet(env, path);

        if (response.status() == 404) {
            return new CliHttpClient.Response(404, "", null);
        }

        if (response.status() >= 300) {
            throw CliException.httpError(response.status(), response.body(), path);
        }

        return response;
    }

    // ---- Internal helpers ----

    private static String identifierToPath(String type, String identifier) {
        if (identifier.startsWith(type + "/")) {
            return "/v1/" + identifier;
        }
        String bucket = TYPE_DEFAULT_BUCKET.getOrDefault(type, "<bucket>");
        throw CliException.validation(
                "Unrecognised identifier '" + identifier
                        + "'. Use a full canonical id '" + type + "/" + bucket + "/<name>' to read from the API,"
                        + " or a plain name (no slashes) to read from file-config.");
    }

    private static CliHttpClient.Response doGet(EnvResolver.ResolvedEnv env, String path) {
        return doGet(env, path, null);
    }

    private static CliHttpClient.Response doGet(EnvResolver.ResolvedEnv env, String path, String query) {
        try {
            return new CliHttpClient(env.apiUrl(), env.apiKey()).get(path, query);
        } catch (CliHttpClient.NetworkException e) {
            throw CliException.network(e.getMessage());
        }
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

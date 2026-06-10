package com.epam.aidial.cli.service;

import com.epam.aidial.cli.client.CliHttpClient;
import com.epam.aidial.cli.exception.CliException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintWriter;
import java.util.Map;

public final class EntityReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static final Map<String, String> TYPE_DEFAULT_BUCKET = Map.ofEntries(
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

    public static JsonNode getEntity(EnvResolver.ResolvedEnv env, String type, String identifier) throws JsonProcessingException {
        if (type.equals("keys") && !identifier.contains("/")) {
            String bucket = TYPE_DEFAULT_BUCKET.getOrDefault(type, "platform");
            throw CliException.validation(
                    "Keys must be specified by full path (e.g. 'keys/" + bucket + "/<name>'). File-sourced keys are not accessible.");
        }

        String path = identifier.contains("/")
                ? identifierToPath(type, identifier)
                : "/v1/admin/config/file/" + type + "/" + identifier;

        CliHttpClient.Response resp = doGet(env, path);

        if (resp.status() >= 300) {
            throw CliException.httpError(resp.status(), resp.body(), path);
        }

        return JSON.readTree(resp.body());
    }

    /**
     * Fetches entity list from both API-managed storage and file-config, merged into a single
     * ArrayNode with a SOURCE field (values: "api" or "file"). Warns via {@code err} when
     * API results are truncated.
     */
    public static ArrayNode getEntities(EnvResolver.ResolvedEnv env, String type, PrintWriter err) {
        CliHttpClient.Response apiResp = getBlobEntitiesResponse(env, type);
        CliHttpClient.Response fileResp = type.equals("keys") ? null : getConfigFileEntitiesResponse(env, type);

        try {
            JsonNode apiNode = JSON.readTree(apiResp.body());
            JsonNode fileNode = fileResp != null ? JSON.readTree(fileResp.body()) : JSON.createObjectNode();

            ArrayNode entries = JSON.createArrayNode();

            JsonNode apiItems = apiNode.get("items");
            if (apiItems != null && apiItems.isArray()) {
                if (apiNode.path("nextToken").isValueNode()) {
                    err.println("[warn] API result truncated at 100 items.");
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

            return entries;
        } catch (JsonProcessingException e) {
            throw CliException.jsonProcessing("Failed to parse response: " + e.getMessage());
        }
    }

    public static ArrayNode getSingleton(EnvResolver.ResolvedEnv env, String type, String name) {
        try {
            JsonNode apiNode = getBlobSingleton(env, type, name);
            JsonNode fileNode = getConfigFileSingleton(env, type, name);

            ArrayNode entries = JSON.createArrayNode();

            if (!apiNode.isEmpty() && apiNode.isObject()) {
                ((ObjectNode) apiNode).put("source", "api");
                entries.add(apiNode);
            }

            if (!fileNode.isEmpty() && fileNode.isObject()) {
                ((ObjectNode) fileNode).put("source", "file");
                entries.add(fileNode);
            }

            return entries;
        } catch (JsonProcessingException e) {
            throw CliException.jsonProcessing("Failed to parse response: " + e.getMessage());
        }
    }

    public static JsonNode getBlobSingleton(EnvResolver.ResolvedEnv env, String type, String name) throws JsonProcessingException {
        String bucket = TYPE_DEFAULT_BUCKET.get(type);
        if (bucket == null) {
            throw CliException.validation("Unsupported entity type: " + type);
        }

        String path = "/v1/" + type + "/" + bucket + "/" + name;
        CliHttpClient.Response response = doGet(env, path);

        if (response.status() == 404) {
            return JSON.createObjectNode();
        }

        if (response.status() >= 300) {
            throw CliException.httpError(response.status(), response.body(), path);
        }

        return JSON.readTree(response.body());
    }

    public static JsonNode getConfigFileSingleton(EnvResolver.ResolvedEnv env, String type, String name) throws JsonProcessingException {
        String path = "/v1/admin/config/file/" + type + "/" + name;
        CliHttpClient.Response response = doGet(env, path);

        if (response.status() == 404) {
            return JSON.createObjectNode();
        }

        if (response.status() >= 300) {
            throw CliException.httpError(response.status(), response.body(), path);
        }

        return JSON.readTree(response.body());
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

    private static CliHttpClient.Response getConfigFileEntitiesResponse(EnvResolver.ResolvedEnv env, String type) {
        String path = "/v1/admin/config/file/" + type;
        CliHttpClient.Response response = doGet(env, path);

        if (response.status() >= 300) {
            throw CliException.httpError(response.status(), response.body(), path);
        }

        return response;
    }

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
        return new CliHttpClient(env.apiUrl(), env.apiKey()).get(path, query);
    }
}

package com.epam.aidial.cli;

import com.epam.aidial.cli.http.CliHttpClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

public final class EntityWriter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();

    private EntityWriter() {
    }

    public static int addEntity(DialCli root, CommandSpec spec, String type, String canonicalId, Path fromFile) {
        String name;
        try {
            name = requireCanonicalId(type, canonicalId);
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        }
        String body;
        try {
            body = loadBodyAsJson(fromFile);
        } catch (NoSuchFileException e) {
            spec.commandLine().getErr().println("File not found: " + fromFile);
            return 2;
        } catch (IOException e) {
            spec.commandLine().getErr().println("Failed to read " + fromFile + ": " + e.getMessage());
            return 2;
        }
        if (root.dryRun) {
            spec.commandLine().getOut().println(body);
            return 0;
        }
        EntityReader.ResolvedEnv resolved = EntityReader.resolveEnv(root, spec);
        if (resolved == null) {
            return 2;
        }
        String path = "/v1/" + type + "/public/" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        CliHttpClient.Response resp;
        try {
            resp = new CliHttpClient(resolved.apiUrl(), resolved.apiKey()).post(path, body);
        } catch (CliHttpClient.NetworkException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }
        if (resp.status() >= 300) {
            spec.commandLine().getErr().println("HTTP " + resp.status() + " " + resp.body());
            return CliHttpClient.toExitCode(resp.status());
        }
        spec.commandLine().getOut().println("Created " + canonicalId);
        return 0;
    }

    public static int updateEntity(DialCli root, CommandSpec spec, String type, String canonicalId,
                                   List<String> sets, String ifMatch) {
        String name;
        try {
            name = requireCanonicalId(type, canonicalId);
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        }
        EntityReader.ResolvedEnv resolved = EntityReader.resolveEnv(root, spec);
        if (resolved == null) {
            return 2;
        }
        String path = "/v1/" + type + "/public/" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        CliHttpClient http = new CliHttpClient(resolved.apiUrl(), resolved.apiKey());
        CliHttpClient.Response getResp;
        try {
            getResp = http.get(path);
        } catch (CliHttpClient.NetworkException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }
        if (getResp.status() >= 300) {
            spec.commandLine().getErr().println("HTTP " + getResp.status() + " " + getResp.body());
            return CliHttpClient.toExitCode(getResp.status());
        }
        ObjectNode merged;
        try {
            JsonNode current = JSON.readTree(getResp.body());
            if (!current.isObject()) {
                spec.commandLine().getErr().println("GET response is not a JSON object: " + getResp.body());
                return 1;
            }
            merged = (ObjectNode) current;
            applySets(merged, sets);
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        } catch (JsonProcessingException e) {
            spec.commandLine().getErr().println("Failed to parse GET response: " + e.getMessage());
            return 1;
        }
        String body;
        try {
            body = JSON.writeValueAsString(merged);
        } catch (JsonProcessingException e) {
            spec.commandLine().getErr().println("Failed to serialize merged body: " + e.getMessage());
            return 1;
        }
        if (root.dryRun) {
            spec.commandLine().getOut().println(body);
            return 0;
        }
        String etag = (ifMatch != null && !ifMatch.isBlank()) ? ifMatch : getResp.etag();
        CliHttpClient.Response putResp;
        try {
            putResp = http.put(path, body, etag);
        } catch (CliHttpClient.NetworkException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }
        if (putResp.status() >= 300) {
            spec.commandLine().getErr().println("HTTP " + putResp.status() + " " + putResp.body());
            return CliHttpClient.toExitCode(putResp.status());
        }
        spec.commandLine().getOut().println("Updated " + canonicalId);
        return 0;
    }

    static void applySets(ObjectNode target, List<String> sets) {
        if (sets == null) {
            return;
        }
        for (String pair : sets) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("--set must be 'path=value'; got '" + pair + "'.");
            }
            String pathExpr = pair.substring(0, eq);
            String rawValue = pair.substring(eq + 1);
            JsonNode value = parseSetValue(rawValue);
            String[] segments = pathExpr.split("\\.", -1);
            for (String segment : segments) {
                if (segment.isEmpty()) {
                    throw new IllegalArgumentException("--set path must not contain empty segments; got '" + pathExpr + "'.");
                }
            }
            ObjectNode cursor = target;
            for (int i = 0; i < segments.length - 1; i++) {
                JsonNode next = cursor.get(segments[i]);
                if (next == null || next.isNull()) {
                    cursor = cursor.putObject(segments[i]);
                } else if (next instanceof ObjectNode existing) {
                    cursor = existing;
                } else {
                    throw new IllegalArgumentException("--set path '" + pathExpr
                            + "' would overwrite a non-object value at '" + segments[i] + "'.");
                }
            }
            cursor.set(segments[segments.length - 1], value);
        }
    }

    private static JsonNode parseSetValue(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (JsonProcessingException e) {
            return TextNode.valueOf(raw);
        }
    }

    private static String requireCanonicalId(String type, String identifier) {
        String prefix = type + "/public/";
        if (!identifier.startsWith(prefix) || identifier.length() == prefix.length()) {
            throw new IllegalArgumentException(
                    "--name must be a canonical id '" + type + "/public/<name>'; got '" + identifier + "'.");
        }
        String name = identifier.substring(prefix.length());
        if (name.contains("/")) {
            throw new IllegalArgumentException(
                    "--name must not contain '/' after the bucket; got '" + identifier + "'.");
        }
        return name;
    }

    private static String loadBodyAsJson(Path file) throws IOException {
        String filename = file.getFileName().toString().toLowerCase();
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        if (filename.endsWith(".yaml") || filename.endsWith(".yml")) {
            JsonNode node = YAML.readTree(raw);
            return JSON.writeValueAsString(node);
        }
        try {
            JSON.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IOException("invalid JSON: " + e.getMessage(), e);
        }
        return raw;
    }
}

package com.epam.aidial.cli;

import com.epam.aidial.cli.http.CliHttpClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

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

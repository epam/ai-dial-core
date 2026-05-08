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

    /**
     * Controller-projected fields the server adds on GET responses (design 03 §4 + §1.5) but
     * rejects on write — `name` is synthesized from the URL, `status` / `source` /
     * `validationWarnings` are projection metadata, never persisted. Stripping them lets a
     * GET → merge → PUT round-trip succeed without a {@code "Unrecognized field"} 400.
     */
    private static final String[] PROJECTION_FIELDS = {"name", "status", "source", "validationWarnings"};

    private EntityWriter() {
    }

    public static int addEntity(DialCli root, CommandSpec spec, String type, String canonicalId, Path fromFile) {
        return addEntity(root, spec, type, "public", canonicalId, fromFile);
    }

    public static int addEntity(DialCli root, CommandSpec spec, String type, String bucket,
                                String canonicalId, Path fromFile) {
        String name;
        try {
            name = requireCanonicalId(type, bucket, canonicalId);
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
        String path = "/v1/" + type + "/" + bucket + "/" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        CliHttpClient.Response resp;
        try {
            resp = new CliHttpClient(resolved.apiUrl(), resolved.apiKey()).post(path, body);
        } catch (CliHttpClient.NetworkException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }
        if (resp.status() >= 300) {
            spec.commandLine().getErr().println(EntityReader.formatHttpError(resp.status(), resp.body(), path));
            return CliHttpClient.toExitCode(resp.status());
        }
        spec.commandLine().getOut().println("Created " + canonicalId);
        return 0;
    }

    public static int updateEntity(DialCli root, CommandSpec spec, String type, String canonicalId,
                                   List<String> sets, String ifMatch) {
        return updateEntity(root, spec, type, "public", canonicalId, sets, ifMatch);
    }

    public static int updateEntity(DialCli root, CommandSpec spec, String type, String bucket,
                                   String canonicalId, List<String> sets, String ifMatch) {
        String name;
        try {
            name = requireCanonicalId(type, bucket, canonicalId);
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        }
        EntityReader.ResolvedEnv resolved = EntityReader.resolveEnv(root, spec);
        if (resolved == null) {
            return 2;
        }
        String path = "/v1/" + type + "/" + bucket + "/" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        CliHttpClient http = new CliHttpClient(resolved.apiUrl(), resolved.apiKey());
        CliHttpClient.Response getResp;
        try {
            getResp = http.get(path);
        } catch (CliHttpClient.NetworkException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }
        if (getResp.status() >= 300) {
            spec.commandLine().getErr().println(EntityReader.formatHttpError(getResp.status(), getResp.body(), path));
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
            merged.remove(java.util.Arrays.asList(PROJECTION_FIELDS));
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
            spec.commandLine().getErr().println(EntityReader.formatHttpError(putResp.status(), putResp.body(), path));
            return CliHttpClient.toExitCode(putResp.status());
        }
        spec.commandLine().getOut().println("Updated " + canonicalId);
        return 0;
    }

    public static int promoteEntity(DialCli root, CommandSpec spec, String type, String kind,
                                    String canonicalId, String sourceEnv, String targetEnv) {
        return promoteEntity(root, spec, type, kind, "public", canonicalId, sourceEnv, targetEnv);
    }

    public static int promoteEntity(DialCli root, CommandSpec spec, String type, String kind, String bucket,
                                    String canonicalId, String sourceEnv, String targetEnv) {
        String simpleName;
        try {
            simpleName = requireCanonicalId(type, bucket, canonicalId);
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        }
        EntityReader.ResolvedEnv source = EntityReader.resolveEnv(root, spec, sourceEnv);
        if (source == null) {
            return 2;
        }
        EntityReader.ResolvedEnv target = EntityReader.resolveEnv(root, spec, targetEnv);
        if (target == null) {
            return 2;
        }
        String path = "/v1/" + type + "/" + bucket + "/" + URLEncoder.encode(simpleName, StandardCharsets.UTF_8);
        CliHttpClient.Response getResp;
        try {
            getResp = new CliHttpClient(source.apiUrl(), source.apiKey()).get(path);
        } catch (CliHttpClient.NetworkException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }
        if (getResp.status() >= 300) {
            spec.commandLine().getErr().println("Source " + source.envName() + ": "
                    + EntityReader.formatHttpError(getResp.status(), getResp.body(), path));
            return CliHttpClient.toExitCode(getResp.status());
        }
        ObjectNode envelope = JSON.createObjectNode();
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("kind", kind);
        manifest.put("name", simpleName);
        try {
            manifest.set("spec", JSON.readTree(getResp.body()));
        } catch (JsonProcessingException e) {
            spec.commandLine().getErr().println("Failed to parse source " + source.envName() + " response: " + e.getMessage());
            return 1;
        }
        envelope.putArray("manifests").add(manifest);
        envelope.put("precheck", true);
        String body;
        try {
            body = JSON.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            spec.commandLine().getErr().println("Failed to serialize apply envelope: " + e.getMessage());
            return 1;
        }
        if (root.dryRun) {
            spec.commandLine().getOut().println(body);
            return 0;
        }
        CliHttpClient.Response applyResp;
        try {
            applyResp = new CliHttpClient(target.apiUrl(), target.apiKey()).post("/v1/admin/apply", body);
        } catch (CliHttpClient.NetworkException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }
        if (applyResp.status() != 200 && applyResp.status() != 422) {
            spec.commandLine().getErr().println("Target " + target.envName() + ": "
                    + EntityReader.formatHttpError(applyResp.status(), applyResp.body(), "/v1/admin/apply"));
            return CliHttpClient.toExitCode(applyResp.status());
        }
        try {
            JsonNode parsed = JSON.readTree(applyResp.body());
            int applied = parsed.path("applied").asInt(0);
            JsonNode results = parsed.path("results");
            if (applied > 0 && applyResp.status() == 200) {
                for (JsonNode r : results) {
                    if ("applied_invalid".equalsIgnoreCase(r.path("status").asText())) {
                        spec.commandLine().getErr().println("warn: "
                                + r.path("entityId").asText("(unknown)")
                                + " applied with validation warnings"
                                + (r.has("error") ? " — " + r.path("error").asText() : ""));
                    }
                }
                spec.commandLine().getOut().println("Promoted " + canonicalId + " from "
                        + source.envName() + " to " + target.envName());
                return 0;
            }
            for (JsonNode r : results) {
                if ("FAILED".equalsIgnoreCase(r.path("status").asText())) {
                    spec.commandLine().getErr().println(
                            r.path("entityId").asText("(unknown)") + ": FAILED"
                                    + (r.has("error") ? " — " + r.path("error").asText() : ""));
                }
            }
            return 2;
        } catch (JsonProcessingException e) {
            spec.commandLine().getErr().println("Failed to parse apply response: " + e.getMessage());
            return 1;
        }
    }

    public static int validateEntity(DialCli root, CommandSpec spec, String type, String kind,
                                     String canonicalId, Path fromFile) {
        return validateEntity(root, spec, type, kind, "public", canonicalId, fromFile);
    }

    public static int validateEntity(DialCli root, CommandSpec spec, String type, String kind, String bucket,
                                     String canonicalId, Path fromFile) {
        String simpleName;
        try {
            simpleName = requireCanonicalId(type, bucket, canonicalId);
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        }
        String specJson;
        try {
            specJson = loadBodyAsJson(fromFile);
        } catch (NoSuchFileException e) {
            spec.commandLine().getErr().println("File not found: " + fromFile);
            return 2;
        } catch (IOException e) {
            spec.commandLine().getErr().println("Failed to read " + fromFile + ": " + e.getMessage());
            return 2;
        }
        ObjectNode envelope = JSON.createObjectNode();
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("kind", kind);
        manifest.put("name", simpleName);
        try {
            manifest.set("spec", JSON.readTree(specJson));
        } catch (JsonProcessingException e) {
            spec.commandLine().getErr().println("Failed to parse spec body: " + e.getMessage());
            return 2;
        }
        envelope.putArray("manifests").add(manifest);
        envelope.put("precheck", true);
        String body;
        try {
            body = JSON.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            spec.commandLine().getErr().println("Failed to serialize validate envelope: " + e.getMessage());
            return 1;
        }
        if (root.dryRun) {
            spec.commandLine().getOut().println(body);
            return 0;
        }
        EntityReader.ResolvedEnv resolved = EntityReader.resolveEnv(root, spec);
        if (resolved == null) {
            return 2;
        }
        CliHttpClient.Response resp;
        try {
            resp = new CliHttpClient(resolved.apiUrl(), resolved.apiKey()).post("/v1/admin/validate", body);
        } catch (CliHttpClient.NetworkException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }
        if (resp.status() != 200 && resp.status() != 422) {
            spec.commandLine().getErr().println(EntityReader.formatHttpError(resp.status(), resp.body(), "/v1/admin/validate"));
            return CliHttpClient.toExitCode(resp.status());
        }
        try {
            JsonNode parsed = JSON.readTree(resp.body());
            int failed = parsed.path("failed").asInt(0);
            JsonNode results = parsed.path("results");
            if (failed == 0 && resp.status() == 200) {
                spec.commandLine().getOut().println("Valid: " + canonicalId);
                return 0;
            }
            for (JsonNode r : results) {
                if ("FAILED".equalsIgnoreCase(r.path("status").asText())) {
                    spec.commandLine().getErr().println(
                            r.path("entityId").asText("(unknown)") + ": FAILED"
                                    + (r.has("error") ? " — " + r.path("error").asText() : ""));
                }
            }
            return 2;
        } catch (JsonProcessingException e) {
            spec.commandLine().getErr().println("Failed to parse validate response: " + e.getMessage());
            return 1;
        }
    }

    public static int deleteEntity(DialCli root, CommandSpec spec, String type, String canonicalId, String ifMatch) {
        return deleteEntity(root, spec, type, "public", canonicalId, ifMatch);
    }

    public static int deleteEntity(DialCli root, CommandSpec spec, String type, String bucket,
                                   String canonicalId, String ifMatch) {
        String name;
        try {
            name = requireCanonicalId(type, bucket, canonicalId);
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        }
        if (root.dryRun) {
            spec.commandLine().getOut().println("Would delete " + canonicalId);
            return 0;
        }
        EntityReader.ResolvedEnv resolved = EntityReader.resolveEnv(root, spec);
        if (resolved == null) {
            return 2;
        }
        String path = "/v1/" + type + "/" + bucket + "/" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        CliHttpClient.Response resp;
        try {
            resp = new CliHttpClient(resolved.apiUrl(), resolved.apiKey()).delete(path, ifMatch);
        } catch (CliHttpClient.NetworkException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }
        if (resp.status() >= 300) {
            spec.commandLine().getErr().println(EntityReader.formatHttpError(resp.status(), resp.body(), path));
            return CliHttpClient.toExitCode(resp.status());
        }
        spec.commandLine().getOut().println("Deleted " + canonicalId);
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

    private static String requireCanonicalId(String type, String bucket, String identifier) {
        String prefix = type + "/" + bucket + "/";
        if (!identifier.startsWith(prefix) || identifier.length() == prefix.length()) {
            throw new IllegalArgumentException(
                    "--name must be a canonical id '" + type + "/" + bucket + "/<name>'; got '" + identifier + "'.");
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

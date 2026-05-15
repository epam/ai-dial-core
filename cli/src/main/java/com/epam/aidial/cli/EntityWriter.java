package com.epam.aidial.cli;

import com.epam.aidial.cli.http.CliHttpClient;
import com.epam.aidial.cli.template.ControlFlowExpander;
import com.epam.aidial.cli.template.TemplateContext;
import com.epam.aidial.cli.template.TemplateException;
import com.epam.aidial.cli.template.TemplateResolver;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private static final String TEMPLATE_AUTO = "auto";

    private EntityWriter() {
    }

    public static int addEntity(DialCli root, CommandSpec spec, String type, String kind,
                                String canonicalId, Path fromFile) {
        return addEntity(root, spec, type, kind, "public", canonicalId, fromFile, null, null);
    }

    public static int addEntity(DialCli root, CommandSpec spec, String type, String kind, String bucket,
                                String canonicalId, Path fromFile) {
        return addEntity(root, spec, type, kind, bucket, canonicalId, fromFile, null, null);
    }

    public static int addEntity(DialCli root, CommandSpec spec, String type, String kind, String bucket,
                                String canonicalId, Path fromFile, String templateName, List<String> paramFlags) {
        String name;
        try {
            name = requireCanonicalId(type, bucket, canonicalId);
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        }
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(root, spec);
        if (resolved == null) {
            return 2;
        }
        Map<String, Object> entityCtx = entityContext(name, kind);
        Map<String, Object> params;
        try {
            params = parseParams(paramFlags);
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        }
        String body;
        try {
            TemplateContext tpl = new TemplateContext(templateName, params,
                    resolved.vars(), entityCtx, resolved.templates());
            body = loadSpecOrFail(fromFile, kind, canonicalId, spec.commandLine().getErr(), tpl);
        } catch (TemplateException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
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
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(root, spec);
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
        return promoteEntity(root, spec, type, kind, "public", canonicalId, sourceEnv, targetEnv, null, null);
    }

    public static int promoteEntity(DialCli root, CommandSpec spec, String type, String kind, String bucket,
                                    String canonicalId, String sourceEnv, String targetEnv) {
        return promoteEntity(root, spec, type, kind, bucket, canonicalId, sourceEnv, targetEnv, null, null);
    }

    public static int promoteEntity(DialCli root, CommandSpec spec, String type, String kind, String bucket,
                                    String canonicalId, String sourceEnv, String targetEnv,
                                    String templateName, List<String> paramFlags) {
        String simpleName;
        try {
            simpleName = requireCanonicalId(type, bucket, canonicalId);
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        }
        EnvResolver.ResolvedEnv source = EnvResolver.resolveEnv(root, spec, sourceEnv);
        if (source == null) {
            return 2;
        }
        EnvResolver.ResolvedEnv target = EnvResolver.resolveEnv(root, spec, targetEnv);
        if (target == null) {
            return 2;
        }
        Map<String, Object> params;
        try {
            params = parseParams(paramFlags);
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
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
        JsonNode sourceSpec;
        try {
            sourceSpec = JSON.readTree(getResp.body());
        } catch (JsonProcessingException e) {
            spec.commandLine().getErr().println("Failed to parse source " + source.envName() + " response: " + e.getMessage());
            return 1;
        }
        Map<String, Object> entityCtx = entityContext(simpleName, kind);
        String effectiveTemplate = templateName;
        if (TEMPLATE_AUTO.equals(templateName)) {
            Map<String, Object> templates = source.templates();
            if (templates == null || templates.isEmpty()) {
                spec.commandLine().getErr().println(
                        "No templates defined in profile; cannot use --template auto. Use --template <name> with an explicit name or omit --template for as-is copy.");
                return 2;
            }
            List<String> matches = autoMatchTemplates(sourceSpec, source, params, entityCtx);
            if (matches.isEmpty()) {
                spec.commandLine().getErr().println(
                        "No template matches the source entity. Available: " + String.join(", ", templates.keySet())
                                + ". Use --template <name> explicitly.");
                return 2;
            }
            if (matches.size() > 1) {
                spec.commandLine().getErr().println(
                        "Multiple templates match: " + String.join(", ", matches)
                                + ". Use --template <name> explicitly.");
                return 2;
            }
            effectiveTemplate = matches.get(0);
        }
        JsonNode mergedSpec;
        try {
            mergedSpec = applyPromoteTemplate(sourceSpec, effectiveTemplate, target, params, entityCtx);
        } catch (TemplateException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        }
        warnSourceHostnames(spec.commandLine().getErr(), canonicalId, mergedSpec, source);
        ObjectNode envelope = JSON.createObjectNode();
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("kind", kind);
        manifest.put("name", simpleName);
        manifest.set("spec", mergedSpec);
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
        return validateEntity(root, spec, type, kind, "public", canonicalId, fromFile, null, null);
    }

    public static int validateEntity(DialCli root, CommandSpec spec, String type, String kind, String bucket,
                                     String canonicalId, Path fromFile) {
        return validateEntity(root, spec, type, kind, bucket, canonicalId, fromFile, null, null);
    }

    public static int validateEntity(DialCli root, CommandSpec spec, String type, String kind, String bucket,
                                     String canonicalId, Path fromFile, String templateName, List<String> paramFlags) {
        String simpleName;
        try {
            simpleName = requireCanonicalId(type, bucket, canonicalId);
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        }
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(root, spec);
        if (resolved == null) {
            return 2;
        }
        Map<String, Object> entityCtx = entityContext(simpleName, kind);
        Map<String, Object> params;
        try {
            params = parseParams(paramFlags);
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        }
        String specJson;
        try {
            TemplateContext tpl = new TemplateContext(templateName, params,
                    resolved.vars(), entityCtx, resolved.templates());
            specJson = loadSpecOrFail(fromFile, kind, canonicalId, spec.commandLine().getErr(), tpl);
        } catch (TemplateException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
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
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(root, spec);
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

    /**
     * Read the file and return JSON for its spec body. If the parsed root looks like a manifest
     * envelope ({@code {kind, name?, spec}} matching {@code sample/dial-cli/manifests/*.yaml}),
     * validate {@code kind} matches {@code expectedKind}, warn when the envelope's {@code name}
     * disagrees with {@code canonicalId}, and return the inner {@code spec} as JSON. Files
     * whose root isn't an envelope pass through (raw-spec backward compatibility).
     *
     * <p>If a {@code templateName} (or any of {@code params}/{@code vars}/{@code entityCtx})
     * is provided, the resolved spec is also passed through {@link TemplateResolver#resolve}
     * — extends/includes are merged, {@code !if}/{@code !for} are expanded, and
     * {@code ${...}} placeholders are substituted.
     */
    static String loadSpecOrFail(Path file, String expectedKind, String canonicalId,
                                 java.io.PrintWriter err, TemplateContext tpl) throws IOException {
        String filename = file.getFileName().toString().toLowerCase();
        boolean yaml = filename.endsWith(".yaml") || filename.endsWith(".yml");
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        if (yaml) {
            raw = ControlFlowExpander.rewriteYaml(raw);
        }
        JsonNode root;
        try {
            root = yaml ? YAML.readTree(raw) : JSON.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IOException("invalid " + (yaml ? "YAML" : "JSON") + ": " + e.getMessage(), e);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new IOException("file is empty");
        }
        JsonNode rawSpec;
        String envelopeTemplate = tpl.templateName();
        Map<String, Object> envelopeParams = tpl.params();
        if (root.isObject() && root.has("kind") && root.has("spec") && root.get("spec").isObject()) {
            JsonNode kindNode = root.get("kind");
            if (!kindNode.isTextual() || kindNode.asText().isBlank()) {
                throw new IOException("manifest envelope has empty 'kind'");
            }
            String declared = kindNode.asText();
            if (!declared.equals(expectedKind)) {
                throw new IOException("manifest 'kind' is '" + declared + "', expected '" + expectedKind + "'");
            }
            JsonNode envName = root.get("name");
            if (envName != null && envName.isTextual() && !envName.asText().isBlank()
                    && canonicalId != null && !envName.asText().equals(canonicalId)) {
                err.println("[warn] manifest 'name' '" + envName.asText()
                        + "' differs from --name '" + canonicalId + "'; using --name");
            }
            // Manifest-level template/params (CLI flags win on conflict).
            if (envelopeTemplate == null || envelopeTemplate.isBlank()) {
                JsonNode templateNode = root.get("template");
                if (templateNode != null && templateNode.isTextual() && !templateNode.asText().isBlank()) {
                    envelopeTemplate = templateNode.asText();
                }
            }
            JsonNode paramsNode = root.get("params");
            if (paramsNode != null && paramsNode.isObject()) {
                Map<String, Object> merged = new HashMap<>();
                paramsNode.fields().forEachRemaining(e -> merged.put(e.getKey(), JSON.convertValue(e.getValue(), Object.class)));
                if (envelopeParams != null) {
                    merged.putAll(envelopeParams);
                }
                envelopeParams = merged;
            }
            rawSpec = root.get("spec");
        } else {
            rawSpec = root;
        }
        TemplateContext effective = new TemplateContext(envelopeTemplate, envelopeParams,
                tpl.vars(), tpl.entityCtx(), tpl.templates());
        JsonNode resolved = TemplateResolver.resolve(rawSpec, effective);
        return JSON.writeValueAsString(resolved);
    }

    static Map<String, Object> entityContext(String simpleName, String kind) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("name", simpleName);
        if (kind != null) {
            ctx.put("type", kind);
        }
        return ctx;
    }

    static Map<String, Object> parseParams(List<String> paramFlags) {
        Map<String, Object> out = new HashMap<>();
        if (paramFlags == null) {
            return out;
        }
        for (String pair : paramFlags) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("--param must be 'key=value'; got '" + pair + "'.");
            }
            String key = pair.substring(0, eq).trim();
            String rawValue = pair.substring(eq + 1);
            out.put(key, parseParamValue(rawValue));
        }
        return out;
    }

    private static Object parseParamValue(String raw) {
        // Comma-separated list: 'a,b,c' → List<String>.
        if (raw.startsWith("[") && raw.endsWith("]")) {
            String inner = raw.substring(1, raw.length() - 1);
            if (inner.isBlank()) {
                return List.of();
            }
            String[] parts = inner.split(",", -1);
            List<String> items = new java.util.ArrayList<>(parts.length);
            for (String p : parts) {
                items.add(p.trim());
            }
            return items;
        }
        return raw;
    }

    /**
     * Reverse-match the source entity against every template in the source env's catalog
     * (design 05 §4 lines 308–318). Returns names of templates whose resolved fields all
     * appear verbatim in {@code sourceSpec}. Templates that fail to resolve (e.g. unresolved
     * {@code ${params.*}} the operator didn't supply) are silently skipped — auto is
     * best-effort; operators with param-using templates pass {@code --template <name>}
     * explicitly per the slice plan.
     */
    private static List<String> autoMatchTemplates(JsonNode sourceSpec, EnvResolver.ResolvedEnv source,
                                                   Map<String, Object> params, Map<String, Object> entityCtx) {
        Map<String, Object> templates = source.templates();
        if (templates == null || templates.isEmpty()) {
            return List.of();
        }
        List<String> matches = new java.util.ArrayList<>();
        for (String name : templates.keySet()) {
            JsonNode resolvedFields;
            try {
                TemplateContext tpl = new TemplateContext(name, params, source.vars(), entityCtx, templates);
                resolvedFields = TemplateResolver.resolveTemplate(tpl);
            } catch (TemplateException e) {
                continue;
            }
            // Template ⊆ spec iff merge-patching it onto spec is a no-op.
            if (JsonMergePatch.apply(sourceSpec, resolvedFields).equals(sourceSpec)) {
                matches.add(name);
            }
        }
        return matches;
    }

    private static JsonNode applyPromoteTemplate(JsonNode sourceSpec, String templateName,
                                                 EnvResolver.ResolvedEnv target,
                                                 Map<String, Object> params,
                                                 Map<String, Object> entityCtx) {
        if (templateName == null || templateName.isBlank()) {
            return sourceSpec;
        }
        TemplateContext tpl = new TemplateContext(templateName, params,
                target.vars(), entityCtx, target.templates());
        JsonNode resolvedTemplate = TemplateResolver.resolveTemplate(tpl);
        // Template-wins merge (design 05 §4 step 4) — inverse of TemplateResolver.resolve's spec-wins (§3.5).
        return TemplateResolver.deepMerge(sourceSpec, resolvedTemplate);
    }

    /** Design 05 §4 step 5 — warns when source-env hostnames survive into the apply payload. */
    private static void warnSourceHostnames(java.io.PrintWriter err, String canonicalId,
                                            JsonNode spec, EnvResolver.ResolvedEnv source) {
        if (source.vars() == null || source.vars().isEmpty()) {
            return;
        }
        Map<String, String> hostnameVars = new HashMap<>();
        for (Map.Entry<String, Object> e : source.vars().entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s && looksLikeHostname(s)) {
                hostnameVars.put(e.getKey(), s);
            }
        }
        if (hostnameVars.isEmpty()) {
            return;
        }
        scanForHostnames(err, canonicalId, spec, "", hostnameVars, source.envName());
    }

    private static boolean looksLikeHostname(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.contains("://")) {
            return true;
        }
        int dot = value.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isLetter(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static void scanForHostnames(java.io.PrintWriter err, String canonicalId, JsonNode node,
                                         String path, Map<String, String> hostnameVars, String envName) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                scanForHostnames(err, canonicalId, entry.getValue(), childPath, hostnameVars, envName);
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                scanForHostnames(err, canonicalId, node.get(i), path + "[" + i + "]", hostnameVars, envName);
            }
        } else if (node.isTextual()) {
            String text = node.asText();
            for (Map.Entry<String, String> e : hostnameVars.entrySet()) {
                if (text.contains(e.getValue())) {
                    err.println("WARN: Entity '" + canonicalId + "' field '" + path
                            + "' contains hostname '" + e.getValue()
                            + "' matching source environment '" + envName + "' (vars." + e.getKey() + "). "
                            + "Consider using --template to transform env-specific fields.");
                }
            }
        }
    }

}

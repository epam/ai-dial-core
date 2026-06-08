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
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.io.PrintWriter;
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
     * Controller-projected fields the server adds on GET responses but
     * rejects on write — `name` is synthesized from the URL, `status` /
     * `validationWarnings` are projection metadata, never persisted. Stripping them lets a
     * GET → merge → PUT round-trip succeed without a {@code "Unrecognized field"} 400.
     * Note: `source` was retired in U.1 and is no longer present in server responses.
     */
    private static final String[] PROJECTION_FIELDS = {"name", "status", "validationWarnings"};

    private static final String TEMPLATE_AUTO = "auto";

    private EntityWriter() {
    }

    public static void addEntity(DialCli root, CommandSpec spec, String type, String kind, String bucket,
                                 String canonicalId, Path fromFile, String templateName, Map<String, Object> params) {
        String name = requireCanonicalId(type, bucket, canonicalId);
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(root);
        Map<String, Object> entityCtx = entityContext(name, kind);
        TemplateContext tpl = new TemplateContext(templateName, params, resolved.vars(), entityCtx, resolved.templates());
        String body = loadSpecOrFail(fromFile, kind, canonicalId, spec.commandLine().getErr(), tpl);
        if (root.dryRun) {
            spec.commandLine().getOut().println(body);
            return;
        }
        String path = "/v1/" + type + "/" + bucket + "/" + name;
        CliHttpClient http = new CliHttpClient(resolved.apiUrl(), resolved.apiKey());
        // PUT with If-None-Match: * — create-only gate (replaces POST after U.0)
        CliHttpClient.Response resp = http.put(path, body, Map.of("If-None-Match", "*"));
        // Server returns 412 (not 409) when If-None-Match: * fails — preserve exit-code 5 contract
        if (resp.status() == 412) {
            throw CliException.alreadyExists(canonicalId);
        }
        if (resp.status() >= 300) {
            throw CliException.httpError(resp.status(), resp.body(), path);
        }
        spec.commandLine().getOut().println("Created " + canonicalId);
    }

    public static void updateEntity(DialCli root, CommandSpec spec, String type, String bucket,
                                    String canonicalId, Map<String, JsonNode> sets, String ifMatch) {
        String name = requireCanonicalId(type, bucket, canonicalId);
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(root);
        String path = "/v1/" + type + "/" + bucket + "/" + name;
        CliHttpClient http = new CliHttpClient(resolved.apiUrl(), resolved.apiKey());
        CliHttpClient.Response getResp = http.get(path);
        if (getResp.status() >= 300) {
            throw CliException.httpError(getResp.status(), getResp.body(), path);
        }
        ObjectNode merged;
        try {
            JsonNode current = JSON.readTree(getResp.body());
            if (!current.isObject()) {
                throw CliException.network("GET response is not a JSON object: " + getResp.body());
            }
            merged = (ObjectNode) current;
        } catch (JsonProcessingException e) {
            throw CliException.network("Failed to parse GET response: " + e.getMessage());
        }
        merged.remove(java.util.Arrays.asList(PROJECTION_FIELDS));
        JsonPatcher.apply(merged, sets);
        String body;
        try {
            body = JSON.writeValueAsString(merged);
        } catch (JsonProcessingException e) {
            throw CliException.network("Failed to serialize merged body: " + e.getMessage());
        }
        if (root.dryRun) {
            spec.commandLine().getOut().println(body);
            return;
        }
        String etag = (ifMatch != null && !ifMatch.isBlank()) ? ifMatch : getResp.etag();
        Map<String, String> headers = new HashMap<>();
        headers.put("If-Match", etag);
        CliHttpClient.Response putResp = http.put(path, body, headers);
        if (putResp.status() >= 300) {
            throw CliException.httpError(putResp.status(), putResp.body(), path);
        }
        spec.commandLine().getOut().println("Updated " + canonicalId);
    }

    public static int promoteEntity(DialCli root, CommandSpec spec, String type, String kind, String bucket,
                                    String canonicalId, String sourceEnv, String targetEnv,
                                    String templateName, Map<String, Object> params) {
        String simpleName = requireCanonicalId(type, bucket, canonicalId);
        EnvResolver.ResolvedEnv source = EnvResolver.resolveEnv(root, sourceEnv);
        EnvResolver.ResolvedEnv target = EnvResolver.resolveEnv(root, targetEnv);
        String path = "/v1/" + type + "/" + bucket + "/" + simpleName;
        CliHttpClient.Response getResp = new CliHttpClient(source.apiUrl(), source.apiKey()).get(path);
        if (getResp.status() >= 300) {
            throw new CliException("Source " + source.envName() + ": "
                    + EntityReader.formatHttpError(getResp.status(), getResp.body(), path),
                    CliHttpClient.toExitCode(getResp.status()));
        }
        ObjectNode sourceSpec;
        try {
            JsonNode entity = JSON.readTree(getResp.body());
            if (!entity.isObject()) {
                throw CliException.network("GET response is not a JSON object: " + getResp.body());
            }
            sourceSpec = (ObjectNode) entity;
        } catch (JsonProcessingException e) {
            throw CliException.network("Failed to parse source " + source.envName() + " response: " + e.getMessage());
        }
        sourceSpec.remove(java.util.Arrays.asList(PROJECTION_FIELDS));
        Map<String, Object> entityCtx = entityContext(simpleName, kind);
        String effectiveTemplate = templateName;
        if (TEMPLATE_AUTO.equals(templateName)) {
            Map<String, Object> templates = source.templates();
            if (templates == null || templates.isEmpty()) {
                throw CliException.validation(
                        "No templates defined in profile; cannot use --template auto. Use --template <name> with an explicit name or omit --template for as-is copy.");
            }
            List<String> matches = autoMatchTemplates(sourceSpec, source, params, entityCtx);
            if (matches.isEmpty()) {
                throw CliException.validation(
                        "No template matches the source entity. Available: " + String.join(", ", templates.keySet())
                                + ". Use --template <name> explicitly.");
            }
            if (matches.size() > 1) {
                throw CliException.validation(
                        "Multiple templates match: " + String.join(", ", matches)
                                + ". Use --template <name> explicitly.");
            }
            effectiveTemplate = matches.get(0);
        }
        JsonNode mergedSpec = applyPromoteTemplate(sourceSpec, effectiveTemplate, target, params, entityCtx);
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
            throw CliException.network("Failed to serialize apply envelope: " + e.getMessage());
        }
        if (root.dryRun) {
            spec.commandLine().getOut().println(body);
            return 0;
        }
        CliHttpClient.Response applyResp =
                new CliHttpClient(target.apiUrl(), target.apiKey()).post("/v1/admin/apply", body);
        if (applyResp.status() != 200 && applyResp.status() != 422) {
            throw new CliException("Target " + target.envName() + ": "
                    + EntityReader.formatHttpError(applyResp.status(), applyResp.body(), "/v1/admin/apply"),
                    CliHttpClient.toExitCode(applyResp.status()));
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
            throw CliException.network("Failed to parse apply response: " + e.getMessage());
        }
    }

    public static int validateEntity(DialCli root, CommandSpec spec, String type, String kind, String bucket,
                                     String canonicalId, Path fromFile, String templateName, Map<String, Object> params) {
        String simpleName = requireCanonicalId(type, bucket, canonicalId);
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(root);
        Map<String, Object> entityCtx = entityContext(simpleName, kind);
        TemplateContext tpl = new TemplateContext(templateName, params, resolved.vars(), entityCtx, resolved.templates());
        String specJson = loadSpecOrFail(fromFile, kind, canonicalId, spec.commandLine().getErr(), tpl);
        ObjectNode envelope = JSON.createObjectNode();
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("kind", kind);
        manifest.put("name", simpleName);
        try {
            manifest.set("spec", JSON.readTree(specJson));
        } catch (JsonProcessingException e) {
            throw CliException.validation("Failed to parse spec body: " + e.getMessage());
        }
        envelope.putArray("manifests").add(manifest);
        envelope.put("precheck", true);
        String body;
        try {
            body = JSON.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw CliException.network("Failed to serialize validate envelope: " + e.getMessage());
        }
        if (root.dryRun) {
            spec.commandLine().getOut().println(body);
            return 0;
        }
        CliHttpClient.Response resp =
                new CliHttpClient(resolved.apiUrl(), resolved.apiKey()).post("/v1/admin/validate", body);
        if (resp.status() != 200 && resp.status() != 422) {
            throw CliException.httpError(resp.status(), resp.body(), "/v1/admin/validate");
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
            throw CliException.network("Failed to parse validate response: " + e.getMessage());
        }
    }

    public static void deleteEntity(DialCli root, CommandSpec spec, String type, String bucket,
                                    String canonicalId, String ifMatch) {
        String name = requireCanonicalId(type, bucket, canonicalId);
        if (root.dryRun) {
            spec.commandLine().getOut().println("Would delete " + canonicalId);
            return;
        }
        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(root);
        String path = "/v1/" + type + "/" + bucket + "/" + name;
        Map<String, String> headers = new HashMap<>();
        headers.put("If-Match", ifMatch);
        CliHttpClient.Response resp = new CliHttpClient(resolved.apiUrl(), resolved.apiKey()).delete(path, headers);
        if (resp.status() >= 300) {
            throw CliException.httpError(resp.status(), resp.body(), path);
        }
        spec.commandLine().getOut().println("Deleted " + canonicalId);
    }

    private static String requireCanonicalId(String type, String bucket, String identifier) {
        String prefix = type + "/" + bucket + "/";
        if (!identifier.startsWith(prefix) || identifier.length() == prefix.length()) {
            throw CliException.validation(
                    "--name must be a canonical id '" + type + "/" + bucket + "/<name>'; got '" + identifier + "'.");
        }
        String name = identifier.substring(prefix.length());
        if (name.contains("/")) {
            throw CliException.validation(
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
    private static String loadSpecOrFail(Path file, String expectedKind, String canonicalId,
                                         PrintWriter err, TemplateContext tpl) {
        String filename = file.getFileName().toString().toLowerCase();
        boolean yaml = filename.endsWith(".yaml") || filename.endsWith(".yml");
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw CliException.validation("File not found: " + file);
        } catch (IOException e) {
            throw CliException.validation("Failed to read " + file + ": " + e.getMessage());
        }
        if (yaml) {
            raw = ControlFlowExpander.rewriteYaml(raw);
        }
        JsonNode root;
        try {
            root = yaml ? YAML.readTree(raw) : JSON.readTree(raw);
        } catch (JsonProcessingException e) {
            throw CliException.validation("invalid " + (yaml ? "YAML" : "JSON") + ": " + e.getMessage());
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw CliException.validation("file is empty");
        }
        JsonNode rawSpec;
        String envelopeTemplate = tpl.templateName();
        Map<String, Object> envelopeParams = tpl.params();
        if (root.isObject() && root.has("kind") && root.has("spec") && root.get("spec").isObject()) {
            JsonNode kindNode = root.get("kind");
            if (!kindNode.isTextual() || kindNode.asText().isBlank()) {
                throw CliException.validation("manifest envelope has empty 'kind'");
            }
            String declared = kindNode.asText();
            if (!declared.equals(expectedKind)) {
                throw CliException.validation("manifest 'kind' is '" + declared + "', expected '" + expectedKind + "'");
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
        try {
            JsonNode resolved = TemplateResolver.resolve(rawSpec, effective);
            return JSON.writeValueAsString(resolved);
        } catch (TemplateException e) {
            throw CliException.validation(e.getMessage());
        } catch (JsonProcessingException e) {
            throw CliException.jsonProcessing("Failed to serialize resolved spec: " + e.getMessage());
        }
    }

    static Map<String, Object> entityContext(String simpleName, String kind) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("name", simpleName);
        if (kind != null) {
            ctx.put("type", kind);
        }
        return ctx;
    }

    /**
     * Reverse-match the source entity against every template in the source env's catalog
     * Returns names of templates whose resolved fields all
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
            } catch (Exception e) {
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
        JsonNode resolvedTemplate;
        try {
            resolvedTemplate = TemplateResolver.resolveTemplate(tpl);
        } catch (TemplateException e) {
            throw CliException.validation(e.getMessage());
        }
        // Template-wins merge — inverse of TemplateResolver.resolve's spec-wins semantics.
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

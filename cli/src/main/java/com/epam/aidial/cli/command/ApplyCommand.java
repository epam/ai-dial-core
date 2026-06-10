package com.epam.aidial.cli.command;

import com.epam.aidial.cli.DialCli;
import com.epam.aidial.cli.client.CliHttpClient;
import com.epam.aidial.cli.exception.CliException;
import com.epam.aidial.cli.exception.ManifestParseException;
import com.epam.aidial.cli.exception.NetworkException;
import com.epam.aidial.cli.exception.TemplateException;
import com.epam.aidial.cli.service.EntityWriter;
import com.epam.aidial.cli.service.EnvResolver;
import com.epam.aidial.cli.service.json.JsonMergePatch;
import com.epam.aidial.cli.service.manifest.Manifest;
import com.epam.aidial.cli.service.manifest.ManifestLoader;
import com.epam.aidial.cli.service.manifest.OverlayResolver;
import com.epam.aidial.cli.service.template.TemplateContext;
import com.epam.aidial.cli.service.template.TemplateResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.UseDefaultConverter;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "apply",
        description = "Apply a fully-resolved manifest file (single or multi-document YAML/JSON).",
        mixinStandardHelpOptions = true
)
public class ApplyCommand implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ParentCommand
    DialCli parent;
    @Spec
    CommandSpec spec;

    @Option(names = {"-f", "--file"}, required = true,
            description = "Manifest file or directory. YAML (.yaml/.yml) supports multiple documents separated by '---'; "
                    + "JSON (.json) accepts a single object or an array of manifests. Directories are walked "
                    + "recursively over .yaml/.yml/.json files; hidden paths (segments starting with '.') are skipped.")
    Path file;

    @Option(names = "--overlay",
            description = "Overlay directory (kind: <Entity>Overlay manifests applying RFC 7396 JSON Merge Patch on base spec,"
                    + " plus empty .disable marker files removing matched base entities). .disable markers require -f to"
                    + " be a directory.")
    Path overlay;

    @Option(names = "--param",
            description = "Template parameter override 'key=value' (repeatable). CLI overrides per-manifest 'params'.",
            converter = {UseDefaultConverter.class, ParamValueConverter.class})
    Map<String, Object> params = new HashMap<>();

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        List<Manifest> manifests;
        try {
            manifests = ManifestLoader.load(file);
        } catch (ManifestParseException e) {
            err.println(e.getMessage());
            return 2;
        }
        if (overlay != null) {
            try {
                manifests = OverlayResolver.apply(manifests, file, overlay);
            } catch (OverlayResolver.OverlayResolveException e) {
                err.println(e.getMessage());
                return 2;
            }
            if (manifests.isEmpty()) {
                err.println("No manifests remain after overlay resolution");
                return 2;
            }
        }

        EnvResolver.ResolvedEnv resolved = EnvResolver.resolveEnv(parent.toCliOptionsDto());

        Map<String, Object> cliParams = params;

        // GETs for Bundle `patch:` entries hit the target env even on --dry-run so the
        // printed envelope reflects the actual apply payload (the patch → merged-spec
        // expansion is what the operator wants to preview). Created lazily so a bundle-free
        // apply with no env reachable still works for dry-run.
        CliHttpClient http = null;
        if (anyPatch(manifests)) {
            http = new CliHttpClient(resolved.apiUrl(), resolved.apiKey());
        }

        ObjectNode envelope = JSON.createObjectNode();
        ArrayNode arr = envelope.putArray("manifests");
        for (Manifest m : manifests) {
            JsonNode resolvedSpec;
            String resolvedName = m.name();
            try {
                Map<String, Object> mergedParams = new HashMap<>();
                if (m.params() != null) {
                    mergedParams.putAll(m.params());
                }
                mergedParams.putAll(cliParams);
                resolvedName = TemplateResolver.resolveString(m.name(), mergedParams, resolved.vars());
                Map<String, Object> entityCtx = EntityWriter.entityContext(resolvedName, m.kind());
                TemplateContext tpl = new TemplateContext(m.templateName(), mergedParams,
                        resolved.vars(), entityCtx, resolved.templates());
                if (m.patch() != null) {
                    JsonNode resolvedPatch = TemplateResolver.resolve(m.patch(), tpl);
                    JsonNode currentSpec = fetchCurrentForPatch(http, m, resolvedName, err);
                    if (currentSpec == null) {
                        return 1;
                    }
                    resolvedSpec = JsonMergePatch.apply(currentSpec, resolvedPatch);
                } else {
                    resolvedSpec = TemplateResolver.resolve(m.spec(), tpl);
                }
            } catch (TemplateException e) {
                err.println(resolvedName + ": " + e.getMessage());
                return 2;
            }
            ObjectNode entry = arr.addObject();
            entry.put("kind", m.kind());
            entry.put("name", resolvedName);
            entry.set("spec", resolvedSpec);
        }
        envelope.put("precheck", true);

        String body;
        try {
            body = JSON.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            err.println("Failed to serialize apply envelope: " + e.getMessage());
            return 1;
        }

        if (parent.dryRun) {
            try {
                out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(envelope));
            } catch (JsonProcessingException e) {
                out.println(body);
            }
            return 0;
        }

        if (http == null) {
            http = new CliHttpClient(resolved.apiUrl(), resolved.apiKey());
        }

        Integer validateExit = runValidate(http, body, err);
        if (validateExit != null) {
            return validateExit;
        }
        return runApply(http, body, out, err);
    }

    private static boolean anyPatch(List<Manifest> manifests) {
        for (Manifest m : manifests) {
            if (m.patch() != null) {
                return true;
            }
        }
        return false;
    }

    // Fetch the target entity's current spec for a Bundle `patch:` entry. Returns an empty
    // ObjectNode on 404 (patch on missing entity merges into `{}`). Returns
    // null on hard failure — the caller surfaces a non-zero exit so the bundle apply aborts
    // rather than silently using stale or partial state.
    private JsonNode fetchCurrentForPatch(CliHttpClient http, Manifest m, String resolvedName,
                                          PrintWriter err) {
        String canonicalId = ManifestLoader.canonicalIdOf(m.kind(), resolvedName);
        String path = "/v1/" + canonicalId;
        CliHttpClient.Response resp;
        try {
            resp = http.get(path);
        } catch (NetworkException e) {
            err.println(resolvedName + ": " + e.getMessage());
            return null;
        }
        if (resp.status() == 404) {
            return JSON.createObjectNode();
        }
        if (resp.status() != 200) {
            err.println(resolvedName + ": GET " + path + " returned HTTP " + resp.status() + " " + resp.body());
            return null;
        }
        try {
            JsonNode node = JSON.readTree(resp.body());
            if (node.isObject()) {
                ((ObjectNode) node).remove(Arrays.asList(EntityWriter.PROJECTION_FIELDS));
            }
            return node;
        } catch (JsonProcessingException e) {
            err.println(resolvedName + ": failed to parse target state: " + e.getMessage());
            return null;
        }
    }

    private Integer runValidate(CliHttpClient http, String body, PrintWriter err) {
        CliHttpClient.Response resp;
        try {
            resp = http.post("/v1/admin/validate", body);
        } catch (NetworkException e) {
            err.println(e.getMessage());
            return 1;
        }
        if (resp.status() != 200 && resp.status() != 422) {
            err.println("HTTP " + resp.status() + " " + resp.body());
            return CliException.toExitCode(resp.status());
        }
        JsonNode parsed;
        try {
            parsed = JSON.readTree(resp.body());
        } catch (JsonProcessingException e) {
            err.println("Failed to parse validate response: " + e.getMessage());
            return 1;
        }
        int failed = parsed.path("failed").asInt(0);
        if (resp.status() == 422 || failed > 0) {
            for (JsonNode r : parsed.path("results")) {
                if ("FAILED".equalsIgnoreCase(r.path("status").asText())) {
                    err.println(r.path("entityId").asText("(unknown)") + ": FAILED"
                            + (r.has("error") ? " — " + r.path("error").asText() : ""));
                }
            }
            return 2;
        }
        return null;
    }

    private int runApply(CliHttpClient http, String body, PrintWriter out, PrintWriter err) {
        CliHttpClient.Response resp;
        try {
            resp = http.post("/v1/admin/apply", body);
        } catch (NetworkException e) {
            err.println(e.getMessage());
            return 1;
        }
        if (resp.status() != 200 && resp.status() != 422) {
            err.println("HTTP " + resp.status() + " " + resp.body());
            return CliException.toExitCode(resp.status());
        }
        JsonNode parsed;
        try {
            parsed = JSON.readTree(resp.body());
        } catch (JsonProcessingException e) {
            err.println("Failed to parse apply response: " + e.getMessage());
            return 1;
        }
        if (resp.status() == 422) {
            for (JsonNode r : parsed.path("results")) {
                if ("FAILED".equalsIgnoreCase(r.path("status").asText())) {
                    err.println(r.path("entityId").asText("(unknown)") + ": FAILED"
                            + (r.has("error") ? " — " + r.path("error").asText() : ""));
                }
            }
            return 2;
        }
        int applied = parsed.path("applied").asInt(0);
        int failed = parsed.path("failed").asInt(0);
        for (JsonNode r : parsed.path("results")) {
            String s = r.path("status").asText();
            String entityId = r.path("entityId").asText("(unknown)");
            if ("applied_invalid".equalsIgnoreCase(s)) {
                err.println("warn: " + entityId + " applied with validation warnings"
                        + (r.has("error") ? " — " + r.path("error").asText() : ""));
            } else if ("FAILED".equalsIgnoreCase(s)) {
                err.println(entityId + ": FAILED"
                        + (r.has("error") ? " — " + r.path("error").asText() : ""));
            }
        }
        out.println("applied: " + applied + ", failed: " + failed);
        return failed == 0 ? 0 : 1;
    }
}

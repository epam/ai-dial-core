package com.epam.aidial.cli;

import com.epam.aidial.cli.http.CliHttpClient;
import com.epam.aidial.cli.template.TemplateContext;
import com.epam.aidial.cli.template.TemplateException;
import com.epam.aidial.cli.template.TemplateResolver;
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

import java.io.PrintWriter;
import java.nio.file.Path;
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
                    + " be a directory. See design 05 §5.2.")
    Path overlay;

    @Option(names = "--param",
            description = "Template parameter override 'key=value' (repeatable). CLI overrides per-manifest 'params'.")
    List<String> params;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        List<ManifestLoader.Manifest> manifests;
        try {
            manifests = ManifestLoader.load(file);
        } catch (ManifestLoader.ManifestParseException e) {
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

        EntityReader.ResolvedEnv resolved = EntityReader.resolveEnv(parent, spec);
        if (resolved == null) {
            return 2;
        }

        Map<String, Object> cliParams;
        try {
            cliParams = EntityWriter.parseParams(params);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        }

        ObjectNode envelope = JSON.createObjectNode();
        ArrayNode arr = envelope.putArray("manifests");
        for (ManifestLoader.Manifest m : manifests) {
            JsonNode resolvedSpec;
            try {
                Map<String, Object> mergedParams = new HashMap<>();
                if (m.params() != null) {
                    mergedParams.putAll(m.params());
                }
                mergedParams.putAll(cliParams);
                Map<String, Object> entityCtx = EntityWriter.entityContext(m.name(), m.kind());
                TemplateContext tpl = new TemplateContext(m.templateName(), mergedParams,
                        resolved.vars(), entityCtx, resolved.templates());
                resolvedSpec = TemplateResolver.resolve(m.spec(), tpl);
            } catch (TemplateException e) {
                err.println(m.name() + ": " + e.getMessage());
                return 2;
            }
            ObjectNode entry = arr.addObject();
            entry.put("kind", m.kind());
            entry.put("name", m.name());
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
            out.println(body);
            return 0;
        }

        CliHttpClient http = new CliHttpClient(resolved.apiUrl(), resolved.apiKey());

        Integer validateExit = runValidate(http, body, err);
        if (validateExit != null) {
            return validateExit;
        }
        return runApply(http, body, out, err);
    }

    private Integer runValidate(CliHttpClient http, String body, PrintWriter err) {
        CliHttpClient.Response resp;
        try {
            resp = http.post("/v1/admin/validate", body);
        } catch (CliHttpClient.NetworkException e) {
            err.println(e.getMessage());
            return 1;
        }
        if (resp.status() != 200 && resp.status() != 422) {
            err.println("HTTP " + resp.status() + " " + resp.body());
            return CliHttpClient.toExitCode(resp.status());
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
        } catch (CliHttpClient.NetworkException e) {
            err.println(e.getMessage());
            return 1;
        }
        if (resp.status() != 200 && resp.status() != 422) {
            err.println("HTTP " + resp.status() + " " + resp.body());
            return CliHttpClient.toExitCode(resp.status());
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

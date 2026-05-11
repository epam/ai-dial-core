package com.epam.aidial.cli;

import com.epam.aidial.cli.template.ControlFlowExpander;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

final class ManifestLoader {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();

    private static final String SETTINGS_SINGLETON_NAME = "global";

    static final Map<String, String> KIND_CANONICAL_PREFIX = Map.of(
            "Model", "models/public/",
            "Application", "applications/public/",
            "ToolSet", "toolsets/public/",
            "Schema", "schemas/public/",
            "Interceptor", "interceptors/platform/",
            "Role", "roles/platform/",
            "Key", "keys/platform/",
            "Route", "routes/platform/");

    private static final Set<String> ALLOWED_KINDS = Set.of(
            "Model", "Application", "ToolSet", "Schema", "Interceptor",
            "Role", "Key", "Route", "Settings");

    private static final Set<String> DEFERRED_KINDS = Set.of(
            "Bundle",
            "ModelOverlay", "ApplicationOverlay", "ToolSetOverlay", "SchemaOverlay",
            "InterceptorOverlay", "RoleOverlay", "KeyOverlay", "RouteOverlay",
            "SettingsOverlay", "FileOverlay", "PromptOverlay", "ConversationOverlay");

    private static final List<String> DEFERRED_FIELDS = List.of("patch", "target");

    private ManifestLoader() {
    }

    static List<Manifest> load(Path path) throws ManifestParseException {
        if (Files.isDirectory(path)) {
            return loadDirectory(path);
        }
        return loadFile(path);
    }

    private static List<Manifest> loadDirectory(Path root) throws ManifestParseException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !hasHiddenSegment(root.relativize(p)))
                    .filter(ManifestLoader::hasManifestExtension)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException | RuntimeException e) {
            throw new ManifestParseException("Failed to walk directory " + root + ": " + e.getMessage());
        }
        List<Manifest> all = new ArrayList<>();
        for (Path f : files) {
            all.addAll(loadFile(f));
        }
        if (all.isEmpty()) {
            throw new ManifestParseException("No manifests found in " + root);
        }
        return all;
    }

    static boolean hasHiddenSegment(Path relative) {
        for (Path seg : relative) {
            if (seg.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    static boolean hasManifestExtension(Path file) {
        return hasManifestExtension(file.getFileName().toString());
    }

    static boolean hasManifestExtension(String filename) {
        String name = filename.toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
    }

    private static List<Manifest> loadFile(Path file) throws ManifestParseException {
        String filename = file.getFileName().toString().toLowerCase();
        boolean yaml = filename.endsWith(".yaml") || filename.endsWith(".yml");
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new ManifestParseException("File not found: " + file);
        } catch (IOException e) {
            throw new ManifestParseException("Failed to read " + file + ": " + e.getMessage());
        }
        if (yaml) {
            content = ControlFlowExpander.rewriteYaml(content);
        }

        List<JsonNode> docs = yaml ? parseYamlDocs(content, file) : parseJsonDocs(content, file);
        if (docs.isEmpty()) {
            throw new ManifestParseException("No manifests found in " + file);
        }
        List<Manifest> manifests = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            manifests.add(toManifest(docs.get(i), i, file));
        }
        return manifests;
    }

    static Set<String> allowedKinds() {
        return ALLOWED_KINDS;
    }

    private static List<JsonNode> parseYamlDocs(String content, Path file) throws ManifestParseException {
        List<JsonNode> docs = new ArrayList<>();
        try (MappingIterator<JsonNode> it = YAML.readerFor(JsonNode.class).readValues(content)) {
            while (it.hasNext()) {
                JsonNode doc = it.next();
                if (doc == null || doc.isMissingNode() || doc.isNull()) {
                    continue;
                }
                docs.add(doc);
            }
        } catch (IOException | RuntimeException e) {
            throw new ManifestParseException("Failed to parse YAML " + file + ": " + e.getMessage());
        }
        return docs;
    }

    private static List<JsonNode> parseJsonDocs(String content, Path file) throws ManifestParseException {
        if (content.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (JsonProcessingException e) {
            throw new ManifestParseException("Failed to parse JSON " + file + ": " + e.getOriginalMessage());
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return List.of();
        }
        if (root.isArray()) {
            List<JsonNode> docs = new ArrayList<>(root.size());
            root.forEach(docs::add);
            return docs;
        }
        return List.of(root);
    }

    private static Manifest toManifest(JsonNode doc, int index, Path file) throws ManifestParseException {
        String where = "manifest #" + (index + 1) + " in " + file;
        if (!doc.isObject()) {
            throw new ManifestParseException(where + ": expected a mapping/object, got " + doc.getNodeType());
        }
        JsonNode kindNode = doc.get("kind");
        if (kindNode == null || !kindNode.isTextual() || kindNode.asText().isBlank()) {
            throw new ManifestParseException(where + ": missing or empty 'kind'");
        }
        String kind = kindNode.asText();
        if (DEFERRED_KINDS.contains(kind)) {
            throw new ManifestParseException(where + ": kind '" + kind
                    + "' is not supported in this MVP (templates, overlays, and bundles are deferred — "
                    + "see IMPLEMENTATION.md §1)");
        }
        if (!ALLOWED_KINDS.contains(kind)) {
            throw new ManifestParseException(where + ": unknown kind '" + kind + "'. Allowed: " + ALLOWED_KINDS);
        }
        for (String f : DEFERRED_FIELDS) {
            if (doc.has(f)) {
                throw new ManifestParseException(where + ": field '" + f
                        + "' is not supported in this MVP (templates, overlays, and bundles are deferred — "
                        + "see IMPLEMENTATION.md §1)");
            }
        }

        JsonNode specNode = doc.get("spec");
        if (specNode == null || specNode.isNull()) {
            throw new ManifestParseException(where + ": missing 'spec'");
        }

        String simpleName;
        if ("Settings".equals(kind)) {
            JsonNode nameNode = doc.get("name");
            if (nameNode == null || !nameNode.isTextual() || !SETTINGS_SINGLETON_NAME.equals(nameNode.asText())) {
                throw new ManifestParseException(where
                        + ": Settings is a singleton — 'name' must be '" + SETTINGS_SINGLETON_NAME + "'");
            }
            simpleName = SETTINGS_SINGLETON_NAME;
        } else {
            JsonNode nameNode = doc.get("name");
            if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()) {
                throw new ManifestParseException(where + ": missing or empty 'name'");
            }
            simpleName = stripCanonical(kind, nameNode.asText(), where);
        }

        String templateName = null;
        JsonNode templateNode = doc.get("template");
        if (templateNode != null && !templateNode.isNull()) {
            if (!templateNode.isTextual() || templateNode.asText().isBlank()) {
                throw new ManifestParseException(where + ": 'template' must be a non-empty string");
            }
            templateName = templateNode.asText();
        }

        Map<String, Object> params = new HashMap<>();
        JsonNode paramsNode = doc.get("params");
        if (paramsNode != null && !paramsNode.isNull()) {
            if (!paramsNode.isObject()) {
                throw new ManifestParseException(where + ": 'params' must be a mapping");
            }
            paramsNode.fields().forEachRemaining(e ->
                    params.put(e.getKey(), JSON.convertValue(e.getValue(), Object.class)));
        }
        return new Manifest(kind, simpleName, specNode, templateName, params, file);
    }

    static String stripCanonical(String kind, String declared, String where) throws ManifestParseException {
        String prefix = KIND_CANONICAL_PREFIX.get(kind);
        if (!declared.startsWith(prefix) || declared.length() == prefix.length()) {
            throw new ManifestParseException(where + ": 'name' must be a canonical id '" + prefix
                    + "<name>'; got '" + declared + "'");
        }
        String simple = declared.substring(prefix.length());
        if (simple.contains("/")) {
            throw new ManifestParseException(where + ": 'name' must not contain '/' after the bucket; got '"
                    + declared + "'");
        }
        return simple;
    }

    /**
     * A parsed manifest entry. {@code source} is the file the manifest was loaded from
     * (used by overlay {@code .disable} matching to compute the file's relative path
     * under the {@code -f} root); it is {@code null} when callers construct a manifest
     * synthetically.
     */
    record Manifest(String kind, String name, JsonNode spec, String templateName,
                    Map<String, Object> params, Path source) { }

    static final class ManifestParseException extends Exception {
        ManifestParseException(String message) {
            super(message);
        }
    }
}

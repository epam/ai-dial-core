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

    private static final String BUNDLE_KIND = "Bundle";

    private static final Set<String> OVERLAY_KINDS = Set.of(
            "ModelOverlay", "ApplicationOverlay", "ToolSetOverlay", "SchemaOverlay",
            "InterceptorOverlay", "RoleOverlay", "KeyOverlay", "RouteOverlay",
            "SettingsOverlay", "FileOverlay", "PromptOverlay", "ConversationOverlay");

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
            manifests.addAll(toManifests(docs.get(i), i, file));
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

    private static List<Manifest> toManifests(JsonNode doc, int index, Path file) throws ManifestParseException {
        String where = "manifest #" + (index + 1) + " in " + file;
        if (!doc.isObject()) {
            throw new ManifestParseException(where + ": expected a mapping/object, got " + doc.getNodeType());
        }
        JsonNode kindNode = doc.get("kind");
        if (kindNode == null || !kindNode.isTextual() || kindNode.asText().isBlank()) {
            throw new ManifestParseException(where + ": missing or empty 'kind'");
        }
        String kind = kindNode.asText();
        if (OVERLAY_KINDS.contains(kind)) {
            throw new ManifestParseException(where + ": kind '" + kind
                    + "' — overlay manifests belong under `--overlay <dir>`, not in the base tree "
                    + "(see 05-cli-design.md §5.2)");
        }
        if (BUNDLE_KIND.equals(kind)) {
            return expandBundle(doc, where, file);
        }
        if (!ALLOWED_KINDS.contains(kind)) {
            throw new ManifestParseException(where + ": unknown kind '" + kind + "'. Allowed: " + ALLOWED_KINDS);
        }
        if (doc.has("target")) {
            throw new ManifestParseException(where + ": field 'target' belongs in `*Overlay` manifests under "
                    + "`--overlay <dir>`, not in a top-level base manifest (see 05-cli-design.md §5.2)");
        }
        if (doc.has("patch")) {
            throw new ManifestParseException(where + ": field 'patch' belongs in Bundle entries or "
                    + "`*Overlay` manifests, not in a top-level base manifest (see 05-cli-design.md §5.3)");
        }
        return List.of(parseEntity(doc, kind, where, file));
    }

    private static Manifest parseEntity(JsonNode doc, String kind, String where, Path file)
            throws ManifestParseException {
        JsonNode specNode = doc.get("spec");
        if (specNode == null || specNode.isNull()) {
            throw new ManifestParseException(where + ": missing 'spec'");
        }
        String simpleName = parseSimpleName(doc, kind, where);
        String templateName = parseTemplateName(doc, where);
        Map<String, Object> params = parseParams(doc, where);
        return new Manifest(kind, simpleName, specNode, null, templateName, params, file);
    }

    private static String parseSimpleName(JsonNode doc, String kind, String where) throws ManifestParseException {
        if ("Settings".equals(kind)) {
            JsonNode nameNode = doc.get("name");
            if (nameNode == null || !nameNode.isTextual() || !SETTINGS_SINGLETON_NAME.equals(nameNode.asText())) {
                throw new ManifestParseException(where
                        + ": Settings is a singleton — 'name' must be '" + SETTINGS_SINGLETON_NAME + "'");
            }
            return SETTINGS_SINGLETON_NAME;
        }
        JsonNode nameNode = doc.get("name");
        if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()) {
            throw new ManifestParseException(where + ": missing or empty 'name'");
        }
        return stripCanonical(kind, nameNode.asText(), where);
    }

    private static String parseTemplateName(JsonNode doc, String where) throws ManifestParseException {
        JsonNode templateNode = doc.get("template");
        if (templateNode == null || templateNode.isNull()) {
            return null;
        }
        if (!templateNode.isTextual() || templateNode.asText().isBlank()) {
            throw new ManifestParseException(where + ": 'template' must be a non-empty string");
        }
        return templateNode.asText();
    }

    private static Map<String, Object> parseParams(JsonNode doc, String where) throws ManifestParseException {
        Map<String, Object> params = new HashMap<>();
        JsonNode paramsNode = doc.get("params");
        if (paramsNode == null || paramsNode.isNull()) {
            return params;
        }
        if (!paramsNode.isObject()) {
            throw new ManifestParseException(where + ": 'params' must be a mapping");
        }
        paramsNode.fields().forEachRemaining(e ->
                params.put(e.getKey(), JSON.convertValue(e.getValue(), Object.class)));
        return params;
    }

    // Expand a Bundle manifest (design 05 §5.3) into one Manifest per entity. The bundle's
    // `params` are visible to every nested entity and override same-named per-entity params
    // (bundle wins on conflict per the design). Each entity declares exactly one of
    // `spec:` (full replacement) or `patch:` (resolved at apply time via GET → JSON Merge
    // Patch against the target env's current state); the produced Manifest carries whichever
    // was set, leaving the other field null.
    private static List<Manifest> expandBundle(JsonNode doc, String where, Path file)
            throws ManifestParseException {
        JsonNode nameNode = doc.get("name");
        if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()) {
            throw new ManifestParseException(where + ": Bundle 'name' missing or empty");
        }
        Map<String, Object> bundleParams = parseParams(doc, where);
        JsonNode entitiesNode = doc.get("entities");
        if (entitiesNode == null || !entitiesNode.isArray() || entitiesNode.isEmpty()) {
            throw new ManifestParseException(where + ": Bundle 'entities' must be a non-empty array");
        }
        List<Manifest> out = new ArrayList<>(entitiesNode.size());
        int i = 0;
        for (JsonNode entry : entitiesNode) {
            String entryWhere = where + " (entity #" + (i + 1) + ")";
            i++;
            if (!entry.isObject()) {
                throw new ManifestParseException(entryWhere + ": expected a mapping/object, got " + entry.getNodeType());
            }
            JsonNode entryKindNode = entry.get("kind");
            if (entryKindNode == null || !entryKindNode.isTextual() || entryKindNode.asText().isBlank()) {
                throw new ManifestParseException(entryWhere + ": missing or empty 'kind'");
            }
            String entryKind = entryKindNode.asText();
            if (BUNDLE_KIND.equals(entryKind)) {
                throw new ManifestParseException(entryWhere + ": nested Bundles are not allowed");
            }
            if (OVERLAY_KINDS.contains(entryKind)) {
                throw new ManifestParseException(entryWhere + ": kind '" + entryKind
                        + "' — overlay manifests belong under `--overlay <dir>`, not in a Bundle entry "
                        + "(see 05-cli-design.md §5.2)");
            }
            if (!ALLOWED_KINDS.contains(entryKind)) {
                throw new ManifestParseException(entryWhere + ": unknown kind '" + entryKind
                        + "'. Allowed: " + ALLOWED_KINDS);
            }
            JsonNode specNode = entry.get("spec");
            JsonNode patchNode = entry.get("patch");
            boolean hasSpec = specNode != null && !specNode.isNull();
            boolean hasPatch = patchNode != null && !patchNode.isNull();
            if (hasSpec == hasPatch) {
                throw new ManifestParseException(entryWhere
                        + ": exactly one of 'spec' or 'patch' is required");
            }
            if (hasPatch && !patchNode.isObject()) {
                throw new ManifestParseException(entryWhere + ": 'patch' must be a mapping");
            }
            String simpleName = parseSimpleName(entry, entryKind, entryWhere);
            String templateName = parseTemplateName(entry, entryWhere);
            Map<String, Object> entryParams = parseParams(entry, entryWhere);
            // Bundle params win on conflict (design 05 §5.3): merge bundle on top of entity.
            Map<String, Object> merged = new HashMap<>(entryParams);
            merged.putAll(bundleParams);
            out.add(new Manifest(entryKind, simpleName,
                    hasSpec ? specNode : null,
                    hasPatch ? patchNode : null,
                    templateName, merged, file));
        }
        return out;
    }

    static String canonicalIdOf(Manifest m) {
        if ("Settings".equals(m.kind())) {
            return "settings/platform/" + m.name();
        }
        String prefix = KIND_CANONICAL_PREFIX.get(m.kind());
        return prefix == null ? m.kind() + "/" + m.name() : prefix + m.name();
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
     * A parsed manifest entry. Exactly one of {@code spec} or {@code patch} is non-null —
     * regular entity manifests carry {@code spec}; Bundle entries with {@code patch:} carry
     * the patch JsonNode to be resolved at apply time (GET → JSON Merge Patch). {@code
     * source} is the file the manifest was loaded from (used by overlay {@code .disable}
     * matching to compute the file's relative path under the {@code -f} root); it is
     * {@code null} when callers construct a manifest synthetically.
     */
    record Manifest(String kind, String name, JsonNode spec, JsonNode patch, String templateName,
                    Map<String, Object> params, Path source) { }

    static final class ManifestParseException extends Exception {
        ManifestParseException(String message) {
            super(message);
        }
    }
}

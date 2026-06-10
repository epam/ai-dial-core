package com.epam.aidial.cli.service.manifest;

import com.epam.aidial.cli.exception.ManifestParseException;
import com.epam.aidial.cli.service.json.JsonMergePatch;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Resolves a list of base manifests against an overlay directory per design 05 §5.2.
 *
 * <p>Two overlay mechanisms:
 * <ul>
 *     <li>{@code kind: <Base>Overlay} manifests apply an RFC 7396 JSON Merge Patch on the
 *         base entity's {@code spec} and may override per-manifest {@code params}.</li>
 *     <li>Empty {@code .disable} marker files remove the corresponding base entity from the
 *         effective set, matched on byte-equal stems and equal relative directories.</li>
 * </ul>
 */
public final class OverlayResolver {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();

    private static final String OVERLAY_SUFFIX = "Overlay";
    private static final String DISABLE_SUFFIX = ".disable";

    private OverlayResolver() {
    }

    public static List<Manifest> apply(List<Manifest> bases,
                                Path baseRoot,
                                Path overlayRoot) throws OverlayResolveException {
        boolean baseRootIsDir = Files.isDirectory(baseRoot);
        OverlayIndex index = buildOverlayIndex(overlayRoot);

        if (!index.disableRelPaths.isEmpty() && !baseRootIsDir) {
            throw new OverlayResolveException(".disable markers require -f to be a directory; "
                    + "got file " + baseRoot);
        }

        List<Manifest> out = new ArrayList<>(bases.size());
        Set<String> matchedDisableKeys = new HashSet<>();

        for (Manifest base : bases) {
            String canonical = ManifestLoader.canonicalIdOf(base);
            String baseRelDir = baseRelDir(base.source(), baseRoot);
            String baseStem = stripLastSuffix(base.source().getFileName().toString());
            String disableKey = disableKey(baseRelDir, baseStem);

            if (index.disableRelPaths.containsKey(disableKey)) {
                matchedDisableKeys.add(disableKey);
                continue;
            }

            OverlayDoc overlay = index.byTarget.remove(canonical);
            if (overlay == null) {
                out.add(base);
                continue;
            }
            JsonNode patchedSpec = overlay.patch == null
                    ? base.spec()
                    : JsonMergePatch.apply(base.spec(), overlay.patch);
            Map<String, Object> mergedParams = new HashMap<>();
            if (base.params() != null) {
                mergedParams.putAll(base.params());
            }
            if (overlay.params != null) {
                mergedParams.putAll(overlay.params);
            }
            out.add(new Manifest(base.kind(), base.name(), patchedSpec, null,
                    base.templateName(), mergedParams, base.source()));
        }

        // Remaining entries in byTarget have no matching base.
        if (!index.byTarget.isEmpty()) {
            Map.Entry<String, OverlayDoc> first = index.byTarget.entrySet().iterator().next();
            throw new OverlayResolveException(first.getValue().where + ": target '"
                    + first.getKey() + "' matches no base manifest");
        }
        for (String disableKey : index.disableRelPaths.keySet()) {
            if (!matchedDisableKeys.contains(disableKey)) {
                throw new OverlayResolveException(index.disableRelPaths.get(disableKey)
                        + ": disable marker matches no base manifest");
            }
        }

        return out;
    }

    private static OverlayIndex buildOverlayIndex(Path overlayRoot) throws OverlayResolveException {
        LinkedHashMap<String, OverlayDoc> byTarget = new LinkedHashMap<>();
        LinkedHashMap<String, Path> disableRelPaths = new LinkedHashMap<>();

        List<Path> files;
        try (Stream<Path> walk = Files.walk(overlayRoot)) {
            files = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !ManifestLoader.hasHiddenSegment(overlayRoot.relativize(p)))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException | RuntimeException e) {
            throw new OverlayResolveException("Failed to walk overlay directory " + overlayRoot
                    + ": " + e.getMessage());
        }

        for (Path file : files) {
            String filename = file.getFileName().toString();
            if (filename.endsWith(DISABLE_SUFFIX)) {
                long size;
                try {
                    size = Files.size(file);
                } catch (IOException e) {
                    throw new OverlayResolveException("Failed to read " + file + ": " + e.getMessage());
                }
                if (size != 0) {
                    throw new OverlayResolveException(file + ": .disable marker must be empty (0 bytes)");
                }
                String relDir = relativeDirOf(file, overlayRoot);
                String stem = filename.substring(0, filename.length() - DISABLE_SUFFIX.length());
                disableRelPaths.put(disableKey(relDir, stem), file);
                continue;
            }
            if (!ManifestLoader.hasManifestExtension(filename)) {
                continue;
            }
            OverlayDoc doc = parseOverlayFile(file);
            if (byTarget.putIfAbsent(doc.target, doc) != null) {
                OverlayDoc previous = byTarget.get(doc.target);
                throw new OverlayResolveException(doc.where + ": duplicate target '" + doc.target
                        + "' (also targeted by " + previous.where + ")");
            }
        }
        return new OverlayIndex(byTarget, disableRelPaths);
    }

    private static OverlayDoc parseOverlayFile(Path file) throws OverlayResolveException {
        String filename = file.getFileName().toString().toLowerCase();
        boolean yaml = filename.endsWith(".yaml") || filename.endsWith(".yml");
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new OverlayResolveException("Failed to read " + file + ": " + e.getMessage());
        }
        List<JsonNode> docs;
        try {
            docs = yaml ? parseYamlDocs(content) : parseJsonDocs(content);
        } catch (IOException e) {
            throw new OverlayResolveException("Failed to parse " + file + ": " + e.getMessage());
        }
        if (docs.size() != 1) {
            throw new OverlayResolveException(file + ": overlay file must contain exactly one document, got "
                    + docs.size());
        }
        return toOverlayDoc(docs.get(0), file);
    }

    private static List<JsonNode> parseYamlDocs(String content) throws IOException {
        List<JsonNode> docs = new ArrayList<>();
        try (MappingIterator<JsonNode> it = YAML.readerFor(JsonNode.class).readValues(content)) {
            while (it.hasNext()) {
                JsonNode doc = it.next();
                if (doc == null || doc.isMissingNode() || doc.isNull()) {
                    continue;
                }
                docs.add(doc);
            }
        }
        return docs;
    }

    private static List<JsonNode> parseJsonDocs(String content) throws JsonProcessingException {
        JsonNode root = JSON.readTree(content);
        return root == null || root.isMissingNode() || root.isNull() ? List.of() : List.of(root);
    }

    private static OverlayDoc toOverlayDoc(JsonNode doc, Path file) throws OverlayResolveException {
        String where = "overlay " + file;
        if (!doc.isObject()) {
            throw new OverlayResolveException(where + ": expected a mapping/object, got " + doc.getNodeType());
        }
        JsonNode kindNode = doc.get("kind");
        if (kindNode == null || !kindNode.isTextual() || kindNode.asText().isBlank()) {
            throw new OverlayResolveException(where + ": missing or empty 'kind'");
        }
        String kind = kindNode.asText();
        if (!kind.endsWith(OVERLAY_SUFFIX)) {
            throw new OverlayResolveException(where + ": kind '" + kind + "' must end with '" + OVERLAY_SUFFIX + "'");
        }
        String baseKind = kind.substring(0, kind.length() - OVERLAY_SUFFIX.length());
        if (!ManifestLoader.allowedKinds().contains(baseKind)) {
            throw new OverlayResolveException(where + ": kind '" + kind + "' targets unknown base kind '"
                    + baseKind + "'");
        }
        if (!ManifestLoader.KIND_CANONICAL_PREFIX.containsKey(baseKind)) {
            // Settings is a singleton with no canonical-id-based addressing; the base manifest's
            // full-replacement spec already covers the per-env-override use case.
            throw new OverlayResolveException(where + ": kind '" + kind
                    + "' is not supported (no canonical-id-addressable base kind)");
        }
        JsonNode targetNode = doc.get("target");
        if (targetNode == null || !targetNode.isTextual() || targetNode.asText().isBlank()) {
            throw new OverlayResolveException(where + ": missing or empty 'target'");
        }
        String target = targetNode.asText();
        validateCanonicalId(baseKind, target, where);

        JsonNode patchNode = doc.get("patch");
        JsonNode paramsNode = doc.get("params");
        if (patchNode == null && paramsNode == null) {
            throw new OverlayResolveException(where + ": must declare 'patch' or 'params'");
        }
        if (patchNode != null && !patchNode.isObject()) {
            throw new OverlayResolveException(where + ": 'patch' must be a mapping");
        }
        Map<String, Object> params = null;
        if (paramsNode != null) {
            if (!paramsNode.isObject()) {
                throw new OverlayResolveException(where + ": 'params' must be a mapping");
            }
            params = new HashMap<>();
            Map<String, Object> finalParams = params;
            paramsNode.fields().forEachRemaining(e ->
                    finalParams.put(e.getKey(), JSON.convertValue(e.getValue(), Object.class)));
        }
        return new OverlayDoc(kind, target, patchNode, params, where);
    }

    private static void validateCanonicalId(String baseKind, String target, String where)
            throws OverlayResolveException {
        try {
            ManifestLoader.stripCanonical(baseKind, target, where);
        } catch (ManifestParseException e) {
            // Rewrap into OverlayResolver's exception type; same contract message.
            throw new OverlayResolveException(e.getMessage());
        }
    }

    private static String baseRelDir(Path source, Path baseRoot) {
        if (source == null || !Files.isDirectory(baseRoot)) {
            return "";
        }
        return relativeDirOf(source, baseRoot);
    }

    private static String relativeDirOf(Path file, Path root) {
        Path relative = root.relativize(file);
        Path parent = relative.getParent();
        return parent == null ? "" : parent.toString().replace('\\', '/');
    }

    private static String stripLastSuffix(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    private static String disableKey(String relDir, String stem) {
        return relDir + " " + stem;
    }

    private record OverlayDoc(String kind, String target, JsonNode patch,
                              Map<String, Object> params, String where) { }

    private record OverlayIndex(LinkedHashMap<String, OverlayDoc> byTarget,
                                LinkedHashMap<String, Path> disableRelPaths) { }

    public static final class OverlayResolveException extends Exception {
        public OverlayResolveException(String message) {
            super(message);
        }
    }
}

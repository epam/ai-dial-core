package com.epam.aidial.core.server.config;

import com.epam.aidial.core.server.controller.ConfigResourceController;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves a platform-bucket blob name for a file-sourced app-type/catalog schema being migrated,
 * or fails with a clear message. The blob name is the last path segment of the schema's {@code $id},
 * falling back to the URI host when the path is empty or absent (e.g. {@code https://dial.com} or
 * {@code https://dial.com/}).
 *
 * <p>A schema is never silently renamed to avoid a collision — collisions are reported as failures
 * instead, individually, per schema, so a dry-run (or real run) result tells the admin exactly which
 * schema(s) need a distinct {@code $id} path.
 */
public final class SchemaMigrationNameResolver {

    private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");

    private SchemaMigrationNameResolver() {
    }

    /**
     * Resolves a blob name for every schema in {@code schemasById} ({@code $id -> raw body}, only
     * not-yet-migrated entries), failing individually for illegal characters or a name collision
     * either within this batch or against {@code existingBlobNameToId} (already-migrated blob name
     * -> its {@code $id}, from listing the platform bucket). Collision comparisons are
     * case-insensitive; a successful resolution's blob name is always the verbatim, non-lowercased
     * derived name.
     */
    public static Map<String, Resolution> resolveNames(Map<String, String> schemasById,
                                                       Map<String, String> idsByExistingBlobName) {
        Map<String, Resolution> resolutions = new LinkedHashMap<>();

        // Phase 1: derive candidates, recording failures immediately
        Map<String, String> candidateBlobNameById = deriveCandidates(schemasById, resolutions);

        // Phase 2: index candidates and existing blobs by lower-cased name
        Map<String, List<String>> idsByCandidateLowerName = indexCandidatesByLowerName(candidateBlobNameById);
        Map<String, String> idByExistingLowerName = lowerCaseKeys(idsByExistingBlobName);

        // Phase 3: detect collisions and record final resolutions
        for (Map.Entry<String, String> entry : candidateBlobNameById.entrySet()) {
            String id = entry.getKey();
            String candidate = entry.getValue();
            resolutions.put(id, resolveCandidate(id, candidate, idsByCandidateLowerName, idByExistingLowerName));
        }

        return resolutions;
    }

    private static Map<String, String> deriveCandidates(Map<String, String> schemasById,
                                                        Map<String, Resolution> resolutions) {
        Map<String, String> candidateBlobNameById = new LinkedHashMap<>();
        for (String id : schemasById.keySet()) {
            Resolution derived = deriveCandidate(id);
            if (derived.isValid()) {
                candidateBlobNameById.put(id, derived.blobName());
            } else {
                resolutions.put(id, derived);
            }
        }
        return candidateBlobNameById;
    }

    private static Map<String, List<String>> indexCandidatesByLowerName(Map<String, String> candidateBlobNameById) {
        Map<String, List<String>> index = new HashMap<>();
        for (Map.Entry<String, String> entry : candidateBlobNameById.entrySet()) {
            index.computeIfAbsent(lower(entry.getValue()), ignored -> new ArrayList<>())
                    .add(entry.getKey());
        }
        return index;
    }

    private static Map<String, String> lowerCaseKeys(Map<String, String> source) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            result.put(lower(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static Resolution resolveCandidate(String id,
                                               String candidate,
                                               Map<String, List<String>> idsByCandidateLowerName,
                                               Map<String, String> idByExistingLowerName) {
        String lowerCandidate = lower(candidate);

        List<String> collidingIds = idsByCandidateLowerName.get(lowerCandidate);
        if (collidingIds.size() > 1) {
            String others = collidingIds.stream()
                    .filter(otherId -> !otherId.equals(id))
                    .collect(Collectors.joining(", "));
            return Resolution.failure("Resolved blob name '" + candidate + "' for schema id '" + id
                    + "' collides with other schema(s) in the config file: " + others);
        }

        String existingId = idByExistingLowerName.get(lowerCandidate);
        if (existingId != null) {
            return Resolution.failure("Resolved blob name '" + candidate + "' for schema id '" + id
                    + "' collides with an existing schema blob (id: '" + existingId + "')");
        }

        return Resolution.success(candidate);
    }

    /**
     * Derives and validates a blob-name candidate for {@code id}, returning either
     * {@link Resolution#success} with the candidate or {@link Resolution#failure}.
     */
    private static Resolution deriveCandidate(String id) {
        URI uri;
        try {
            uri = new URI(id);
        } catch (URISyntaxException e) {
            return Resolution.failure("Schema id '" + id + "' is not a valid URI");
        }

        String candidate = lastPathSegment(uri.getPath());
        if (candidate == null) {
            String host = uri.getHost();
            candidate = (host == null || host.isEmpty()) ? null : host;
        }
        if (candidate == null) {
            return Resolution.failure("Cannot derive a blob name for schema id '" + id
                    + "': no path segment or host");
        }

        Pattern entityNamePattern = ConfigResourceController.ENTITY_NAME_PATTERN;
        if (!entityNamePattern.matcher(candidate).matches()) {
            return Resolution.failure("Resolved blob name '" + candidate + "' for schema id '" + id
                    + "' has illegal characters: must match " + entityNamePattern.pattern());
        }

        return Resolution.success(candidate);
    }

    private static String lastPathSegment(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = TRAILING_SLASHES.matcher(path).replaceAll("");
        int slash = trimmed.lastIndexOf('/');
        String segment = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        return segment.isEmpty() ? null : segment;
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    public record Resolution(String blobName, String error) {

        public static Resolution success(String blobName) {
            return new Resolution(blobName, null);
        }

        public static Resolution failure(String error) {
            return new Resolution(null, error);
        }

        public boolean isValid() {
            return error == null;
        }
    }
}

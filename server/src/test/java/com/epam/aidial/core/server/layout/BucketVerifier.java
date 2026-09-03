package com.epam.aidial.core.server.layout;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Checks a bucket that has been copied to the tenant-rooted layout: everything arrived, and nothing extra did.
 *
 * <p>Object counts are not the check. A count that is off by one says something is wrong but not what, and a
 * count that matches says nothing about <em>which</em> objects matched — so this compares the two trees as
 * sets of paths mapped through the same transform the copier used, and names the one file whose absence would
 * be silent.
 */
@UtilityClass
public class BucketVerifier {

    /**
     * Governs read access to the whole public space. Its absence does not fail loudly: no document means an
     * empty rule map, and {@code RuleMatcher} returns true for everything, so a migration that dropped it
     * would publish the entire public space to everyone and every object count would still add up.
     */
    public static final String RULES_DOCUMENT = "public/rules/rules";

    public record Result(int sourceObjects, int destinationObjects, List<String> problems) {
        public boolean clean() {
            return problems.isEmpty();
        }
    }

    public static Result verify(Path legacyRoot, Path tenantRoot, String tenantId, List<String> typeFolders) {
        Map<String, Path> source = index(legacyRoot);
        Map<String, Path> destination = index(tenantRoot);

        List<String> problems = new ArrayList<>();
        Map<String, String> expected = new LinkedHashMap<>();
        source.keySet().forEach(path -> expected.put(BucketCopier.toTenantPath(path, tenantId, typeFolders), path));

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            Path arrived = destination.get(entry.getKey());
            if (arrived == null) {
                problems.add("missing at the destination: " + entry.getValue() + " → " + entry.getKey());
                continue;
            }
            if (!sameContent(source.get(entry.getValue()), arrived)) {
                problems.add("content differs: " + entry.getValue());
            }
            problems.addAll(compareMetadata(entry.getValue(), source.get(entry.getValue()), arrived));
        }

        destination.keySet().stream()
                .filter(path -> !expected.containsKey(path))
                .forEach(path -> problems.add("present at the destination with no source: " + path));

        problems.addAll(verifyRulesDocument(source, destination, tenantId, typeFolders));
        return new Result(source.size(), destination.size(), problems);
    }

    /**
     * The rules document by name, not as one row in a count.
     */
    private static List<String> verifyRulesDocument(Map<String, Path> source, Map<String, Path> destination,
                                                    String tenantId, List<String> typeFolders) {
        String sourcePath = source.keySet().stream()
                .filter(path -> path.endsWith(RULES_DOCUMENT))
                .findFirst()
                .orElse(null);
        if (sourcePath == null) {
            // Nothing was published, so there is no rules document to lose. Said out loud rather than passed
            // over: a verifier that silently skips this check on an empty public space is one that would also
            // skip it on a migration that dropped the file.
            return List.of("note: the source has no " + RULES_DOCUMENT + ", so public access was not verified");
        }

        String expected = BucketCopier.toTenantPath(sourcePath, tenantId, typeFolders);
        if (!destination.containsKey(expected)) {
            return List.of("THE RULES DOCUMENT DID NOT ARRIVE: " + sourcePath + " → " + expected
                    + ". Its absence fails open — the whole public space becomes readable by everyone.");
        }
        if (!sameContent(source.get(sourcePath), destination.get(expected))) {
            return List.of("THE RULES DOCUMENT ARRIVED WITH DIFFERENT CONTENT: " + expected);
        }
        return List.of();
    }

    /**
     * Compares the blob's metadata, not only its bytes.
     *
     * <p>Bytes alone are not enough to call an object migrated. Whether it is compressed is recorded beside
     * it, not inside it, so a copy that keeps every byte and drops that flag reads back as a parse error —
     * and an inventory that only checksums content reports such a bucket as perfectly migrated. The etag,
     * author and creation time live in the same place.
     */
    @SneakyThrows
    private static List<String> compareMetadata(String path, Path source, Path destination) {
        Map<String, String> from = metadata(source);
        Map<String, String> to = metadata(destination);
        if (from.isEmpty() && to.isEmpty()) {
            return List.of();
        }

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> attribute : from.entrySet()) {
            String arrived = to.get(attribute.getKey());
            if (arrived == null) {
                problems.add("metadata lost: " + path + " is missing '" + attribute.getKey()
                        + "' at the destination (was: " + attribute.getValue() + ")");
            } else if (!arrived.equals(attribute.getValue())) {
                problems.add("metadata differs: " + path + " '" + attribute.getKey() + "' was "
                        + attribute.getValue() + ", arrived as " + arrived);
            }
        }
        return problems;
    }

    /**
     * The blob store's own attributes. System attributes are skipped — they belong to the filesystem rather
     * than to the object, and cannot be carried across by a copy.
     */
    @SneakyThrows
    private static Map<String, String> metadata(Path file) {
        UserDefinedFileAttributeView view = Files.getFileAttributeView(file, UserDefinedFileAttributeView.class);
        if (view == null) {
            return Map.of();
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        for (String name : view.list()) {
            if (name.startsWith("com.apple.")) {
                continue;
            }
            ByteBuffer buffer = ByteBuffer.allocate(view.size(name));
            view.read(name, buffer);
            attributes.put(name, new String(buffer.array(), StandardCharsets.ISO_8859_1));
        }
        return attributes;
    }

    @SneakyThrows
    private static boolean sameContent(Path left, Path right) {
        return Files.mismatch(left, right) == -1;
    }

    @SneakyThrows
    private static Map<String, Path> index(Path root) {
        Map<String, Path> files = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).sorted()
                    .forEach(path -> files.put(root.relativize(path).toString(), path));
        }
        return files;
    }
}

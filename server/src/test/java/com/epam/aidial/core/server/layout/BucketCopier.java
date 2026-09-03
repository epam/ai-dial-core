package com.epam.aidial.core.server.layout;

import com.epam.aidial.core.storage.resource.TenantLayoutTransform;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Copies a blob tree from the legacy layout into the tenant-rooted one.
 *
 * <p>A stand-in for the migrator P2 will build, not the migrator itself: it copies bytes and rewrites paths,
 * with none of the throughput, resumability or re-encryption a real one needs. It exists so the verifier has
 * something to verify before the real one lands, and so the questions the verifier asks are settled first.
 */
@Slf4j
@UtilityClass
public class BucketCopier {

    /**
     * Splits a physical path into the bucket location and what follows, by finding the resource-type folder.
     * The two halves are what {@link TenantLayoutTransform} converts, and a path is only made of those two
     * plus the resource path within the type.
     */
    private record SplitPath(String location, String typeFolder, String rest) {
    }

    @SneakyThrows
    public static int copy(Path legacyRoot, Path tenantRoot, String tenantId, List<String> typeFolders) {
        int copied = 0;
        for (Path source : list(legacyRoot)) {
            String relative = legacyRoot.relativize(source).toString();
            String transformed = toTenantPath(relative, tenantId, typeFolders);

            Path destination = tenantRoot.resolve(transformed);
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination);
            copyMetadata(source, destination);
            copied++;
        }
        return copied;
    }

    /**
     * The path a migrated object lands at. Package-visible so the verifier maps source to destination the same
     * way rather than reimplementing the rule.
     */
    static String toTenantPath(String legacyPath, String tenantId, List<String> typeFolders) {
        SplitPath split = split(legacyPath, typeFolders);
        return TenantLayoutTransform.toTenantLocation(split.location(), tenantId)
                + TenantLayoutTransform.toTenantTypeFolder(split.typeFolder())
                + "/" + split.rest();
    }

    private static SplitPath split(String legacyPath, List<String> typeFolders) {
        String[] segments = legacyPath.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (!typeFolders.contains(segments[i])) {
                continue;
            }
            String location = i == 0 ? "" : String.join("/", List.of(segments).subList(0, i)) + "/";
            String rest = String.join("/", List.of(segments).subList(i + 1, segments.length));
            return new SplitPath(location, segments[i], rest);
        }
        throw new IllegalArgumentException("No resource type folder in: " + legacyPath);
    }

    /**
     * Carries the blob's metadata across, not just its bytes.
     *
     * <p>The filesystem blob store keeps content-encoding, content-type and the user metadata — author,
     * created_at, etag — in extended attributes, and a plain byte copy silently drops them. A compressed
     * resource then arrives as gzip bytes nobody knows to decompress, and reads back as a parse error rather
     * than as anything obviously missing.
     */
    @SneakyThrows
    private static void copyMetadata(Path source, Path destination) {
        UserDefinedFileAttributeView from = Files.getFileAttributeView(source, UserDefinedFileAttributeView.class);
        UserDefinedFileAttributeView to = Files.getFileAttributeView(destination, UserDefinedFileAttributeView.class);
        if (from == null || to == null) {
            return;
        }

        for (String name : from.list()) {
            ByteBuffer buffer = ByteBuffer.allocate(from.size(name));
            from.read(name, buffer);
            buffer.flip();
            try {
                to.write(name, buffer);
            } catch (IOException systemAttribute) {
                // Some attributes belong to the OS rather than the blob store and cannot be set by hand.
                log.debug("Could not copy attribute {} of {}", name, source);
            }
        }
    }

    @SneakyThrows
    private static List<Path> list(Path root) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).sorted().forEach(files::add);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + root, e);
        }
        return files;
    }
}

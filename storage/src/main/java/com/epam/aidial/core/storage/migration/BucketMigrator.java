package com.epam.aidial.core.storage.migration;

import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.resource.TenantLayoutTransformer;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Copies one bucket's objects from their legacy paths to their tenant-rooted ones, through the blob store's
 * own server-side copy. Copy, never move: the legacy tree is left untouched, which is what makes a rollback
 * a state change rather than a second migration.
 *
 * <p>Call it on a bucket that is {@link BucketMigrationState#MIGRATING} and already flushed. Nothing here
 * enforces that — the copy is a mechanism, and the seal belongs to whoever is sequencing the migration — but
 * a copy taken while the bucket accepts writes is a copy of a moving target.
 */
@Slf4j
public class BucketMigrator {

    private static final int PAGE_SIZE = 1000;
    private static final String SEPARATOR = ResourceDescriptor.PATH_SEPARATOR;

    /**
     * Storage folder names, which are what a physical path carries — not the url segments, which differ for
     * two types. Derived from the enum rather than listed, so a new resource type cannot be forgotten here.
     */
    private static final Set<String> TYPE_FOLDERS = Arrays.stream(ResourceTypes.values())
            .map(ResourceType::group)
            .collect(Collectors.toUnmodifiableSet());

    private static final String ENCRYPTION_KEYS = ResourceTypes.ENCRYPTION_KEYS.group();

    private final BlobStorage blobStore;
    private final String tenantId;

    public BucketMigrator(BlobStorage blobStore, String tenantId) {
        this.blobStore = blobStore;
        this.tenantId = tenantId;
    }

    /**
     * Copies the whole bucket, its content encryption keys first: every other encrypted payload in the bucket
     * decrypts through them, and a reader that finds the key missing at the resolved path mints a fresh one
     * rather than failing, which orphans the ciphertext permanently.
     */
    public Result copyBucket(String bucketLocation) {
        Result keys = copy(bucketLocation, bucketLocation + ENCRYPTION_KEYS + SEPARATOR, null);
        Result rest = copy(bucketLocation, bucketLocation, ENCRYPTION_KEYS);
        Result total = keys.plus(rest);

        log.info("Copied {} ({} objects, {} bytes) to the tenant-rooted layout", bucketLocation, total.objects(), total.bytes());
        return total;
    }

    private Result copy(String bucketLocation, String listPrefix, String excludedTypeFolder) {
        int objects = 0;
        long bytes = 0;
        String marker = null;

        do {
            PageSet<? extends StorageMetadata> page = blobStore.list(listPrefix, marker, PAGE_SIZE, true);
            for (StorageMetadata metadata : page) {
                if (metadata.getType() != StorageType.BLOB) {
                    continue;
                }

                String source = metadata.getName();
                Split split = split(bucketLocation, source);
                if (split.typeFolder().equals(excludedTypeFolder)) {
                    continue;
                }

                copyObject(source, destination(split));
                objects++;
                bytes += metadata.getSize() == null ? 0 : metadata.getSize();
            }
            marker = page.getNextMarker();
        } while (marker != null);

        return new Result(objects, bytes);
    }

    /**
     * Carries the object's metadata across explicitly rather than relying on the provider's default copy
     * directive. Whether a resource is compressed lives in {@code content-encoding} beside the bytes, not
     * inside them: an object that arrives without it reads back as a parse error, while an inventory and a
     * checksum both still pass.
     */
    private void copyObject(String source, String destination) {
        Map<String, String> userMetadata = blobStore.meta(source).getUserMetadata();
        blobStore.copy(source, destination, userMetadata);
    }

    private String destination(Split split) {
        return TenantLayoutTransformer.toTenantLocation(split.location(), tenantId)
                + TenantLayoutTransformer.toTenantTypeFolder(split.typeFolder())
                + SEPARATOR + split.pathWithinType();
    }

    /**
     * Splits a physical path at its resource-type folder, the boundary the transform converts on either side
     * of. The search starts after the bucket location, so a principal whose id happens to read like a type
     * folder cannot move the boundary; what lies between is a sub-bucket suffix, as under {@code public/}.
     */
    private static Split split(String bucketLocation, String key) {
        List<String> segments = List.of(key.substring(bucketLocation.length()).split(SEPARATOR));

        for (int i = 0; i < segments.size(); i++) {
            if (!TYPE_FOLDERS.contains(segments.get(i))) {
                continue;
            }

            String suffix = i == 0 ? "" : String.join(SEPARATOR, segments.subList(0, i)) + SEPARATOR;
            String pathWithinType = String.join(SEPARATOR, segments.subList(i + 1, segments.size()));
            return new Split(bucketLocation + suffix, segments.get(i), pathWithinType);
        }

        throw new IllegalArgumentException("No resource type folder in: " + key);
    }

    private record Split(String location, String typeFolder, String pathWithinType) {
    }

    public record Result(int objects, long bytes) {

        Result plus(Result other) {
            return new Result(objects + other.objects, bytes + other.bytes);
        }
    }
}

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
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;
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
     * The bucket locations a copy of this prefix would touch, which is not always just the one asked for:
     * the platform synthesizes sub-buckets under {@code public/}, such as a public function app's source
     * folder at {@code public/deployments/<id>/}, and one copy carries them all.
     *
     * <p>They matter because a migration state is matched by exact location, not by prefix. Sealing and
     * promoting {@code public/} alone would leave every sub-bucket resolving to the legacy layout over bytes
     * that have already been copied — reads keep working, so nothing looks wrong, while writes go on landing
     * in a tree the migration has left behind. Whoever seals a bucket must seal what this returns.
     */
    public Set<String> locations(String bucketLocation) {
        Set<String> locations = new TreeSet<>();
        walk(bucketLocation, metadata -> locations.add(split(bucketLocation, metadata.getName()).location()));
        return locations;
    }

    /**
     * Copies the whole bucket, its content encryption keys first: every other encrypted payload in the bucket
     * decrypts through them, and a reader that finds the key missing at the resolved path mints a fresh one
     * rather than failing, which orphans the ciphertext permanently.
     */
    public Result copyBucket(String bucketLocation) {
        // Two passes over the same listing, split by resource type rather than by prefix. An earlier version
        // narrowed the first pass to <bucket>/encryption_keys/ while the second excluded the type folder at
        // any depth, so a key belonging to a synthesized sub-bucket — public/deployments/<id>/encryption_keys
        // — was copied by neither. The bucket then arrived migrated with no key, and the reader that found
        // none minted a fresh one, which is the orphaning this ordering exists to prevent. Listing twice is
        // the price of ordering the copy without holding the bucket in memory.
        Result keys = copy(bucketLocation, ENCRYPTION_KEYS::equals);
        Result rest = copy(bucketLocation, typeFolder -> !ENCRYPTION_KEYS.equals(typeFolder));
        Result total = keys.plus(rest);

        log.info("Copied {} ({} objects, {} bytes) to the tenant-rooted layout", bucketLocation, total.objects(), total.bytes());
        return total;
    }

    private Result copy(String bucketLocation, Predicate<String> typeFolder) {
        Counter counter = new Counter();
        walk(bucketLocation, metadata -> {
            String source = metadata.getName();
            Split split = split(bucketLocation, source);
            if (!typeFolder.test(split.typeFolder())) {
                return;
            }

            copyObject(source, destination(split));
            counter.objects++;
            counter.bytes += metadata.getSize() == null ? 0 : metadata.getSize();
            counter.locations.add(split.location());
        });

        return new Result(counter.objects, counter.bytes, counter.locations);
    }

    /**
     * Pages through every blob under a prefix. Directory entries are skipped: the filesystem provider
     * reports them, the cloud ones do not, and neither is an object to copy.
     */
    private void walk(String listPrefix, Consumer<StorageMetadata> consumer) {
        String marker = null;
        do {
            PageSet<? extends StorageMetadata> page = blobStore.list(listPrefix, marker, PAGE_SIZE, true);
            for (StorageMetadata metadata : page) {
                if (metadata.getType() == StorageType.BLOB) {
                    consumer.accept(metadata);
                }
            }
            marker = page.getNextMarker();
        } while (marker != null);
    }

    private static final class Counter {
        private int objects;
        private long bytes;
        private final Set<String> locations = new TreeSet<>();
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

    /**
     * @param locations the bucket locations the copy actually touched. The caller seals buckets, so it is the
     *                  caller that has to be told when a copy reached further than the location it named.
     */
    public record Result(int objects, long bytes, Set<String> locations) {

        Result plus(Result other) {
            Set<String> merged = new TreeSet<>(locations);
            merged.addAll(other.locations);
            return new Result(objects + other.objects, bytes + other.bytes, merged);
        }
    }
}

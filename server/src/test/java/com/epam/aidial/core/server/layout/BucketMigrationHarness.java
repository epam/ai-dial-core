package com.epam.aidial.core.server.layout;

import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.cache.CacheClientFactory;
import com.epam.aidial.core.storage.migration.BucketMigrationRegistry;
import com.epam.aidial.core.storage.migration.BucketMigrationState;
import com.epam.aidial.core.storage.migration.BucketMigrator;
import com.epam.aidial.core.storage.resource.StorageLayouts;
import com.epam.aidial.core.storage.resource.TenantRootedStorageLayout;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TimerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import org.redisson.api.RedissonClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/**
 * Drives the per-bucket migration state of a deployment from outside it, so a bucket can be sealed, drained
 * and promoted while a core serves the same store. There is no API for this yet, and this is not one — it is
 * the operator's hands for a local experiment, and it lives in the test source set so it ships nowhere.
 *
 * <pre>
 * ./gradlew :server:bucketMigration --args="&lt;settings.json&gt; migrate Users/&lt;id&gt;/"
 * </pre>
 *
 * <p>{@code migrate} is seal → wait → flush → copy → promote → wait; each step is also a command of its own,
 * for a run that stops to inspect the store between them, and {@code revert} is the rollback drill.
 *
 * <p>It reads the same settings file the core does, so it addresses the same blob store, the same Redis and
 * the same prefix. Its {@link ResourceService} is built on a timer service that never fires: the harness must
 * flush the bucket it was asked about and nothing else.
 */
public final class BucketMigrationHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Schedules nothing. A real one would start the store-wide sync sweep, which would write other buckets
     * out from under whoever is migrating them.
     */
    private static final TimerService INERT_TIMERS = (initialDelay, delay, task) -> () -> {
    };

    private BucketMigrationHarness() {
    }

    @SneakyThrows
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: <settings.json> <state|window|seal|flush|copy|promote|revert|prepare|finish|migrate> [bucketLocation]");
            System.exit(2);
        }

        JsonNode settings = MAPPER.readTree(Files.readString(Path.of(args[0])));
        String command = args[1];
        String bucketLocation = args.length > 2 ? args[2] : null;

        try (Session session = new Session(settings)) {
            session.run(command, bucketLocation);
        }
    }

    private static final class Session implements AutoCloseable {

        private final BlobStorage blobStore;
        private final RedissonClient redis;
        private final BucketMigrationRegistry registry;
        private final ResourceService resources;
        private final BucketMigrator migrator;

        @SneakyThrows
        private Session(JsonNode settings) {
            ObjectNode storageSettings = (ObjectNode) settings.get("storage").deepCopy();
            JsonNode layout = storageSettings.remove("layout");
            blobStore = new BlobStorage(MAPPER.treeToValue(storageSettings, Storage.class));

            redis = CacheClientFactory.create(settings.get("redis"));
            LockService lockService = new LockService(redis, blobStore.getPrefix());

            long refreshPeriod = refreshPeriod(layout);
            registry = new BucketMigrationRegistry(blobStore, lockService, INERT_TIMERS, refreshPeriod);
            String tenantId = tenantId(layout);
            migrator = new BucketMigrator(blobStore, tenantId);
            // The same composition the core does, so a flush resolves the bucket's paths exactly as it will.
            StorageLayouts.useLayoutPerBucket(new TenantRootedStorageLayout(tenantId), registry);

            resources = new ResourceService(INERT_TIMERS, redis, blobStore, lockService,
                    MAPPER.treeToValue(settings.get("resources"), ResourceService.Settings.class),
                    blobStore.getPrefix(), () -> null, registry);
        }

        private void run(String command, String bucketLocation) throws InterruptedException {
            switch (command) {
                case "state" -> System.out.println(bucketLocation == null
                        ? "pass a bucket location to read its state"
                        : bucketLocation + " " + registry.resolve(bucketLocation));
                case "window" -> System.out.println(registry.propagationWindow() + " ms");
                case "seal" -> {
                    registry.seal(require(bucketLocation));
                    System.out.println("sealed " + bucketLocation + "; wait " + registry.propagationWindow()
                            + " ms before touching it");
                }
                case "flush" -> {
                    resources.flushBucket(require(bucketLocation));
                    System.out.println("flushed " + bucketLocation);
                }
                case "promote" -> {
                    registry.promote(require(bucketLocation));
                    System.out.println("promoted " + bucketLocation);
                }
                case "revert" -> {
                    registry.revert(require(bucketLocation));
                    System.out.println("reverted " + bucketLocation + " to the legacy layout");
                }
                case "copy" -> copy(require(bucketLocation));
                case "prepare" -> prepare(require(bucketLocation));
                case "finish" -> finish(require(bucketLocation));
                case "migrate" -> {
                    prepare(require(bucketLocation));
                    copy(bucketLocation);
                    finish(bucketLocation);
                }
                default -> throw new IllegalArgumentException("Unknown command: " + command);
            }
        }

        /**
         * Seals everything the copy will touch, not only the location named. One copy of {@code public/}
         * carries its sub-buckets with it, and a state is matched by exact location, so sealing the parent
         * alone leaves them writable while their bytes are being copied.
         */
        private void prepare(String bucketLocation) throws InterruptedException {
            for (String location : covered(bucketLocation)) {
                registry.seal(location);
                System.out.println("sealed " + location);
            }

            System.out.println("waiting " + registry.propagationWindow() + " ms");
            Thread.sleep(registry.propagationWindow());

            for (String location : covered(bucketLocation)) {
                resources.flushBucket(location);
                System.out.println("flushed " + location);
            }
        }

        /**
         * The location asked for, plus every location a copy of it would reach. Enumerated before the seal,
         * so it is read from a bucket still accepting writes: a location that appears afterwards is caught
         * after the copy instead, where it is reported rather than silently promoted.
         */
        private Set<String> covered(String bucketLocation) {
            Set<String> locations = new TreeSet<>(migrator.locations(bucketLocation));
            locations.add(bucketLocation);
            return locations;
        }

        private void copy(String bucketLocation) {
            // The seal is what makes the copy meaningful; copying an open bucket copies a moving target.
            BucketMigrationState state = registry.resolve(bucketLocation);
            if (state != BucketMigrationState.MIGRATING) {
                throw new IllegalStateException(
                        "Seal %s before copying it — it is %s".formatted(bucketLocation, state));
            }

            BucketMigrator.Result result = migrator.copyBucket(bucketLocation);
            System.out.println("copied " + result.objects() + " objects, " + result.bytes() + " bytes");

            // A copy that reached a location nobody sealed took a moving target, and promoting the parent
            // would leave that one resolving to the legacy tree over bytes already copied.
            for (String location : result.locations()) {
                BucketMigrationState reached = registry.resolve(location);
                if (reached != BucketMigrationState.MIGRATING) {
                    throw new IllegalStateException(("The copy of %s reached %s, which is %s rather than "
                            + "sealed — seal it and copy again").formatted(bucketLocation, location, reached));
                }
            }
        }

        private void finish(String bucketLocation) throws InterruptedException {
            for (String location : covered(bucketLocation)) {
                registry.promote(location);
                System.out.println("promoted " + location);
            }

            System.out.println("waiting " + registry.propagationWindow() + " ms");
            Thread.sleep(registry.propagationWindow());
            System.out.println("done — " + bucketLocation + " now resolves to the tenant-rooted layout");
        }

        private static String require(String bucketLocation) {
            if (bucketLocation == null) {
                throw new IllegalArgumentException("A bucket location is required, for example Users/<id>/");
            }
            return bucketLocation;
        }

        private static long refreshPeriod(JsonNode layout) {
            JsonNode migration = layout == null ? null : layout.get("migration");
            long seconds = migration == null || migration.get("refreshPeriodSeconds") == null
                    ? 10
                    : migration.get("refreshPeriodSeconds").asLong();
            return seconds * 1000;
        }

        private static String tenantId(JsonNode layout) {
            JsonNode tenant = layout == null ? null : layout.get("defaultTenant");
            return tenant == null ? "default" : tenant.asText();
        }

        @Override
        public void close() {
            registry.close();
            resources.close();
            redis.shutdown();
            blobStore.close();
        }
    }
}

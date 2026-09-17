package com.epam.aidial.core.storage.service;

import com.epam.aidial.core.storage.FileUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.migration.BucketMigrationState;
import com.epam.aidial.core.storage.resource.LegacyStorageLayout;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.resource.StorageLayouts;
import com.epam.aidial.core.storage.resource.TenantRootedStorageLayout;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ResourceServiceMigrationTest {

    private static final String SEALED_BUCKET = "Users/sealed/";
    private static final String OPEN_BUCKET = "Users/open/";

    private final Map<String, BucketMigrationState> stateByBucket = new HashMap<>();

    private RedisServer server;
    private RedissonClient client;
    private ResourceService service;
    private BlobStorage storage;
    private Path testDir;

    @BeforeEach
    void init() throws IOException {
        try {
            server = RedisServer.newRedisServer()
                    .port(16376)
                    .bind("127.0.0.1")
                    .setting("maxmemory 8M")
                    .setting("maxmemory-policy volatile-lfu")
                    .build();
            server.start();

            Config config = new Config();
            config.useSingleServer().setAddress("redis://localhost:16376");
            client = Redisson.create(config);

            testDir = FileUtil.baseTestPath(ResourceServiceMigrationTest.class);
            FileUtil.createDir(testDir.resolve("test"));
            ObjectMapper mapper = new ObjectMapper();
            String blobStorageConfig = """
                    {
                        "bucket": "test",
                        "provider": "filesystem",
                        "identity": "access-key",
                        "credential": "secret-key",
                        "prefix": "test-2",
                        "overrides": {
                          "jclouds.filesystem.basedir": %s
                        }
                      }
                    """.formatted(mapper.writeValueAsString(testDir.toString()));
            storage = new BlobStorage(mapper.readValue(blobStorageConfig, Storage.class));

            String serviceConfig = """
                    {
                     "maxSize" : 67108864,
                     "maxSizeToCache": 1048576,
                     "syncPeriod": 60000,
                     "syncDelay": 120000,
                     "syncBatch": 4096,
                     "cacheExpiration": 300000,
                     "compressionMinSize": 256
                    }
                    """;
            ResourceService.Settings settings = mapper.readValue(serviceConfig, ResourceService.Settings.class);
            service = new ResourceService(Mockito.mock(TimerService.class), client, storage,
                    new LockService(client, "test-2"), settings, "test-2", () -> null,
                    bucketLocation -> stateByBucket.getOrDefault(bucketLocation, BucketMigrationState.LEGACY));
        } catch (Throwable e) {
            destroy();
            throw e;
        }
    }

    @AfterEach
    void destroy() throws IOException {
        try {
            if (client != null) {
                client.shutdown();
            }
            if (storage != null) {
                storage.close();
            }
        } finally {
            if (server != null) {
                server.stop();
            }
            FileUtil.deleteDir(testDir);
        }
    }

    @Test
    public void testSealedBucketRefusesWrites() {
        ResourceDescriptor resource = resource(SEALED_BUCKET, "chat");
        service.putResource(resource, "before the seal", EtagHeader.ANY);

        seal(SEALED_BUCKET);

        HttpException error = assertThrows(HttpException.class,
                () -> service.putResource(resource, "during the seal", EtagHeader.ANY));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        assertThrows(HttpException.class, () -> service.deleteResource(resource, EtagHeader.ANY));
        assertThrows(HttpException.class, () -> service.computeResource(resource, body -> "computed"));
    }

    @Test
    public void testSealedBucketKeepsServingReads() {
        ResourceDescriptor resource = resource(SEALED_BUCKET, "chat");
        service.putResource(resource, "before the seal", EtagHeader.ANY);

        seal(SEALED_BUCKET);

        assertEquals("before the seal", service.getResource(resource));
        assertNotNull(service.getResourceMetadata(resource));
    }

    @Test
    public void testSealIsPerBucket() {
        ResourceDescriptor open = resource(OPEN_BUCKET, "chat");
        seal(SEALED_BUCKET);

        service.putResource(open, "unaffected", EtagHeader.ANY);
        assertEquals("unaffected", service.getResource(open));
    }

    @Test
    public void testCopyIsRefusedWhenEitherSideIsSealed() {
        ResourceDescriptor sealed = resource(SEALED_BUCKET, "chat");
        ResourceDescriptor open = resource(OPEN_BUCKET, "chat");
        service.putResource(sealed, "content", EtagHeader.ANY);
        service.putResource(open, "content", EtagHeader.ANY);

        seal(SEALED_BUCKET);

        assertThrows(HttpException.class, () -> service.copyResource(sealed, resource(OPEN_BUCKET, "copy")));
        assertThrows(HttpException.class, () -> service.copyResource(open, resource(SEALED_BUCKET, "copy")));
    }

    @Test
    public void testFlushBucketWritesPendingChangesToBlobStore() {
        ResourceDescriptor pending = resource(SEALED_BUCKET, "chat");
        service.putResource(pending, "cached only", EtagHeader.ANY);
        // A small resource is staged in redis; the blob holds an empty stub until it syncs, so a copy taken
        // now would carry nothing.
        assertEquals("", blobBody(pending));

        seal(SEALED_BUCKET);
        service.flushBucket(SEALED_BUCKET);

        assertEquals("cached only", blobBody(pending));
    }

    @Test
    public void testFlushBucketLeavesOtherBucketsPending() {
        ResourceDescriptor sealed = resource(SEALED_BUCKET, "chat");
        ResourceDescriptor open = resource(OPEN_BUCKET, "chat");
        service.putResource(sealed, "sealed body", EtagHeader.ANY);
        service.putResource(open, "open body", EtagHeader.ANY);

        seal(SEALED_BUCKET);
        service.flushBucket(SEALED_BUCKET);

        assertEquals("sealed body", blobBody(sealed));
        assertEquals("", blobBody(open));
    }

    private void seal(String bucketLocation) {
        stateByBucket.put(bucketLocation, BucketMigrationState.MIGRATING);
    }

    private static ResourceDescriptor resource(String bucketLocation, String name) {
        return new ResourceDescriptor(ResourceTypes.CONVERSATION, name, List.of(), "bucket", bucketLocation, false);
    }

    private String blobBody(ResourceDescriptor descriptor) {
        try (var stream = storage.load(descriptor.getAbsoluteFilePath()).getPayload().openStream()) {
            return new String(stream.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    public void testDrainingPublicLeavesItsSubBucketsPending() {
        ResourceDescriptor rules = resource("public/", "rules");
        ResourceDescriptor source = resource("public/deployments/app1/", "source");
        service.putResource(rules, "public body", EtagHeader.ANY);
        service.putResource(source, "sub-bucket body", EtagHeader.ANY);
        seal("public/");
        seal("public/deployments/app1/");

        // A prefix would take both. The sub-bucket is its own bucket, sealed and drained on its own.
        service.flushBucket("public/");
        assertEquals("public body", blobBody(rules));
        assertEquals("", blobBody(source));

        service.flushBucket("public/deployments/app1/");
        assertEquals("sub-bucket body", blobBody(source));
    }

    @Test
    public void testDrainingMigratedBucketsStaysWithinEachOfThem() {
        StorageLayouts.useLayoutPerBucket(new TenantRootedStorageLayout("acme"),
                bucketLocation -> stateByBucket.getOrDefault(bucketLocation, BucketMigrationState.LEGACY));
        try {
            ResourceDescriptor rules = resource("public/", "rules");
            ResourceDescriptor model = resource("platform/", "model");
            ResourceDescriptor chat = resource(OPEN_BUCKET, "chat");
            for (String location : List.of("public/", "platform/", OPEN_BUCKET)) {
                stateByBucket.put(location, BucketMigrationState.MIGRATED);
            }
            service.putResource(rules, "public body", EtagHeader.ANY);
            service.putResource(model, "platform body", EtagHeader.ANY);
            service.putResource(chat, "user body", EtagHeader.ANY);

            // In the tenant-rooted layout public/ is .org/acme/, which holds every user bucket, and platform/
            // is the root of the store. A prefix drain of either would take the user's pending write with it.
            service.flushBucket("public/");
            assertEquals("public body", blobBody(rules));
            assertEquals("", blobBody(chat));

            service.flushBucket("platform/");
            assertEquals("platform body", blobBody(model));
            assertEquals("", blobBody(chat));

            service.flushBucket(OPEN_BUCKET);
            assertEquals("user body", blobBody(chat));
        } finally {
            StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
        }
    }
}

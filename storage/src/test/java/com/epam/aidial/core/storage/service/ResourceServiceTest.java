package com.epam.aidial.core.storage.service;

import com.epam.aidial.core.storage.FileUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResourceServiceTest {

    private RedisServer server;
    private RedissonClient client;
    private ResourceService service;
    private BlobStorage storage;
    private Path testDir;

    @BeforeEach
    void init() throws IOException {
        try {
            server = RedisServer.newRedisServer()
                    .port(16371)
                    .bind("127.0.0.1")
                    .setting("maxmemory 8M")
                    .setting("maxmemory-policy volatile-lfu")
                    .build();
            server.start();

            Config config = new Config();
            config.useSingleServer().setAddress("redis://localhost:16371");

            client = Redisson.create(config);

            testDir = FileUtil.baseTestPath(ResourceServiceTest.class);
            FileUtil.createDir(testDir.resolve("test"));
            String blobStorageConfig = """
                    {
                        "bucket": "test",
                        "provider": "filesystem",
                        "identity": "access-key",
                        "credential": "secret-key",
                        "prefix": "test-2",
                        "overrides": {
                          "jclouds.filesystem.basedir": "%s"
                        }
                      }
                    """.formatted(testDir.toString());
            ObjectMapper mapper = new ObjectMapper();
            Storage storageConfig = mapper.readValue(blobStorageConfig, Storage.class);
            storage = new BlobStorage(storageConfig);

            TimerService timerService = Mockito.mock(TimerService.class);
            LockService lockService = new LockService(client, null);

            String serviceConfig = """
                    {
                     "maxSize" : 67108864,
                     "maxSizeToCache": 1048576,
                     "syncPeriod": 60000,
                     "syncDelay": 120000,
                     "syncBatch": 4096,
                     "cacheExpiration": 300000,
                     "compressionMinSize": 256,
                     "heartbeatPeriod": 60000
                    }
                    """;
            ResourceService.Settings settings = mapper.readValue(serviceConfig, ResourceService.Settings.class);
            service = new ResourceService(timerService, client, storage, lockService, settings, null);
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
    public void testListResources() {
        byte[] body = "1234567890".getBytes();
        ResourceDescriptor resourceDescriptor = new ResourceDescriptor(ResourceTypes.APPLICATION, null, List.of(), "public", "public/", true);
        String path = resourceDescriptor.getAbsoluteFilePath();
        for (int i = 0; i < 589; i++) {
            storage.store(path + "/" + "app_" + i, "application/octet-stream", null, Map.of("author", "user"), body);
        }
        List<Pair<ResourceItemMetadata, String>> result = service.listResources(resourceDescriptor, resourceFolderMetadata -> {
        });
        assertNotNull(result);
        assertEquals(589, result.size());
        TreeSet<Integer> ids = new TreeSet<>();
        for (var item : result) {
            String name = item.getKey().getName();
            assertEquals("user", item.getKey().getAuthor());
            int id = Integer.parseInt(name.substring(name.indexOf('_') + 1));
            ids.add(id);
        }
        assertEquals(589, ids.size());
        assertEquals(0, ids.getFirst());
        assertEquals(588, ids.getLast());

        // previous resources should be already in Redis
        // but these resources are missed
        for (int i = 589; i < 700; i++) {
            storage.store(path + "/" + "app_" + i, "application/octet-stream", null, Map.of("author", "user"), body);
        }

        result = service.listResources(resourceDescriptor, resourceFolderMetadata -> {
        });
        assertNotNull(result);
        assertEquals(700, result.size());

        ids = new TreeSet<>();
        for (var item : result) {
            String name = item.getKey().getName();
            assertEquals("user", item.getKey().getAuthor());
            int id = Integer.parseInt(name.substring(name.indexOf('_') + 1));
            ids.add(id);
        }
        assertEquals(700, ids.size());
        assertEquals(0, ids.getFirst());
        assertEquals(699, ids.getLast());
    }


    @Test
    public void testEncode() {
        assertNull(ResourceService.encode(null));
        assertEquals("", ResourceService.encode(""));
        assertEquals("base58_24SWdWXor", ResourceService.encode("{abñ}"));
    }

    @Test
    public void testDecode() {
        assertNull(ResourceService.decode(null));
        assertEquals("", ResourceService.decode(""));
        assertEquals("{abc}", ResourceService.decode("{abc}"));
        assertEquals("{abñ}", ResourceService.decode("base58_24SWdWXor"));
    }

    @Test
    public void testCleanupTempFolder() {
        byte[] body = "1234567890".getBytes();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime now = LocalDateTime.now();

        String expiredFolder = now.minusHours(3).format(formatter);
        String recentFolder = now.minusMinutes(30).format(formatter);
        String currentFolder = now.format(formatter);

        String expiredFile = ResourceService.TEMP_FOLDER + "/" + expiredFolder + "/expired";
        String recentFile = ResourceService.TEMP_FOLDER + "/" + recentFolder + "/recent";
        String currentFile = ResourceService.TEMP_FOLDER + "/" + currentFolder + "/current";

        storage.store(expiredFile, "application/octet-stream", null, Map.of(), body);
        storage.store(recentFile, "application/octet-stream", null, Map.of(), body);
        storage.store(currentFile, "application/octet-stream", null, Map.of(), body);

        service.cleanupTempFolder();

        assertFalse(storage.exists(expiredFile));
        // a file uploaded within the last hour is kept because its folder hour may still be in progress
        assertTrue(storage.exists(recentFile));
        assertTrue(storage.exists(currentFile));
    }
}

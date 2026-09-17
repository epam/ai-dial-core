package com.epam.aidial.core.storage.migration;

import com.epam.aidial.core.storage.FileUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BucketMigratorTest {

    private static final String TENANT = "acme";

    private BlobStorage storage;
    private Path testDir;
    private BucketMigrator migrator;

    @BeforeEach
    void init() throws IOException {
        try {
            testDir = FileUtil.baseTestPath(BucketMigratorTest.class);
            FileUtil.createDir(testDir.resolve("test"));
            ObjectMapper mapper = new ObjectMapper();
            String config = """
                    {
                        "bucket": "test",
                        "provider": "filesystem",
                        "identity": "access-key",
                        "credential": "secret-key",
                        "overrides": {
                          "jclouds.filesystem.basedir": %s
                        }
                      }
                    """.formatted(mapper.writeValueAsString(testDir.toString()));
            storage = new BlobStorage(mapper.readValue(config, Storage.class));
            migrator = new BucketMigrator(storage, TENANT);
        } catch (Throwable e) {
            destroy();
            throw e;
        }
    }

    @AfterEach
    void destroy() throws IOException {
        if (storage != null) {
            storage.close();
        }
        FileUtil.deleteDir(testDir);
    }

    @Test
    public void testUserBucketLandsUnderTheTenantRoot() {
        put("Users/u1/conversations/folder/chat1", "one");
        put("Users/u1/files/notes.txt", "two");

        BucketMigrator.Result result = migrator.copyBucket("Users/u1/");

        assertEquals(2, result.objects());
        assertEquals("one", body(".org/acme/.users/u1/.conversations/folder/chat1"));
        assertEquals("two", body(".org/acme/.users/u1/.files/notes.txt"));
    }

    @Test
    public void testLegacyTreeIsLeftInPlace() {
        put("Users/u1/conversations/chat1", "one");

        migrator.copyBucket("Users/u1/");

        assertEquals("one", body("Users/u1/conversations/chat1"));
    }

    @Test
    public void testMetadataTravelsWithTheObject() {
        storage.store("Users/u1/conversations/chat1", "application/json", "gzip",
                Map.of("etag", "\"abc\"", "author", "u1", "created_at", "42"), "compressed".getBytes());

        migrator.copyBucket("Users/u1/");

        BlobMetadata metadata = storage.meta(".org/acme/.users/u1/.conversations/chat1");
        assertEquals("gzip", metadata.getContentMetadata().getContentEncoding(),
                "content-encoding lives beside the bytes; losing it makes the resource unreadable");
        assertEquals("application/json", metadata.getContentMetadata().getContentType());
        assertEquals("\"abc\"", metadata.getUserMetadata().get("etag"));
        assertEquals("u1", metadata.getUserMetadata().get("author"));
        assertEquals("42", metadata.getUserMetadata().get("created_at"));
    }

    @Test
    public void testEncryptionKeysAreCopiedFirst() {
        put("Users/u1/conversations/chat1", "one");
        put("Users/u1/encryption_keys/key", "cek");
        put("Users/u1/files/notes.txt", "two");

        List<String> order = new ArrayList<>();
        BlobStorage recording = Mockito.spy(storage);
        Mockito.doAnswer(invocation -> {
            order.add(invocation.getArgument(1));
            return invocation.callRealMethod();
        }).when(recording).copy(Mockito.anyString(), Mockito.anyString(), Mockito.any());

        new BucketMigrator(recording, TENANT).copyBucket("Users/u1/");

        assertEquals(3, order.size());
        assertTrue(order.get(0).contains("encryption_keys"),
                "the CEK must arrive before anything that decrypts through it: " + order);
    }

    @Test
    public void testPublicSubBucketKeepsItsSuffix() {
        put("public/deployments/app1/files/source.py", "print(1)");

        migrator.copyBucket("public/");

        assertEquals("print(1)", body(".org/acme/deployments/app1/.files/source.py"));
    }

    @Test
    public void testSubBucketEncryptionKeysAreCopied() {
        put("public/deployments/app1/encryption_keys/cek", "the key");
        put("public/deployments/app1/files/source.py", "print(1)");

        migrator.copyBucket("public/");

        // locations() reports this sub-bucket, so it gets sealed and promoted. Arriving there without its
        // key would leave the reader to mint a fresh one over content the old key encrypted.
        assertEquals("the key", body(".org/acme/deployments/app1/.encryption_keys/cek"));
        assertEquals("print(1)", body(".org/acme/deployments/app1/.files/source.py"));
    }

    @Test
    public void testLocationsReportsThePublicSubBucketsTheCopyWouldReach() {
        put("public/rules/rules", "{}");
        put("public/deployments/app1/files/source.py", "print(1)");
        put("public/deployments/app2/files/other.py", "print(2)");

        // A migration state is matched by exact location, so sealing and promoting "public/" alone would
        // leave both deployment sub-buckets resolving to the legacy layout over bytes already copied.
        assertEquals(Set.of("public/", "public/deployments/app1/", "public/deployments/app2/"),
                migrator.locations("public/"));
    }

    @Test
    public void testCopyReportsEveryLocationItTouched() {
        put("public/rules/rules", "{}");
        put("public/deployments/app1/files/source.py", "print(1)");

        BucketMigrator.Result result = migrator.copyBucket("public/");

        assertEquals(Set.of("public/", "public/deployments/app1/"), result.locations());
    }

    @Test
    public void testLocationsOfPrincipalBucketIsJustItself() {
        put("Users/u1/conversations/chat", "{}");
        put("Users/u1/prompts/folder/p1", "{}");

        assertEquals(Set.of("Users/u1/"), migrator.locations("Users/u1/"));
    }

    @Test
    public void testSystemBucketLandsAboveTheTenant() {
        put("background_jobs/background_jobs/job1", "job");

        migrator.copyBucket("background_jobs/");

        assertEquals("job", body(".system/background_jobs/.background_jobs/job1"));
    }

    @Test
    public void testObjectWithoutTypeFolderIsRejected() {
        put("Users/u1/not_a_type/thing", "x");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> migrator.copyBucket("Users/u1/"));
        assertTrue(error.getMessage().contains("not_a_type"), error.getMessage());
    }

    @Test
    public void testCopyIsRepeatable() {
        put("Users/u1/conversations/chat1", "one");

        migrator.copyBucket("Users/u1/");
        BucketMigrator.Result second = migrator.copyBucket("Users/u1/");

        assertEquals(1, second.objects(), "an interrupted run resumes by simply running again");
        assertEquals("one", body(".org/acme/.users/u1/.conversations/chat1"));
    }

    private void put(String path, String body) {
        storage.store(path, "application/json", null, Map.of("author", "u1"), body.getBytes());
    }

    private String body(String path) {
        Blob blob = storage.load(path);
        if (blob == null) {
            throw new AssertionError("Nothing at " + path);
        }
        try (InputStream stream = blob.getPayload().openStream()) {
            return new String(stream.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

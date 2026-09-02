package com.epam.aidial.core.server.layout;

import com.epam.aidial.core.server.FileUtil;
import com.epam.aidial.core.storage.resource.LegacyStorageLayout;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.resource.StorageLayouts;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Migrates a bucket and then serves it: copy a legacy tree into the tenant-rooted layout, check everything
 * arrived, and boot a tenant-rooted core onto the copy to read it all back.
 *
 * <p>This is the one instrument that crosses layouts. The other two start empty and each run reads only its
 * own writes, so neither can see a failure that only happens to data written under one layout and read under
 * the other — which is the entire risk P2 carries.
 */
@Tag("layout-diff")
public class LayoutBucketVerifierTest {

    private static final String TENANT = "migrated-tenant";

    private static final int SOURCE_REDIS_PORT = 16375;
    private static final int MIGRATED_REDIS_PORT = 16376;

    /**
     * Where the blob store puts objects inside the data directory: the configured bucket, then the prefix.
     */
    private static final String BLOB_ROOT = "test/test-2";

    private static final List<String> TYPE_FOLDERS =
            Arrays.stream(ResourceTypes.values()).map(ResourceTypes::group).distinct().toList();

    @AfterEach
    public void restoreLegacyLayout() {
        StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
    }

    @Test
    @SneakyThrows
    public void testMigratedBucketIsCompleteAndReadable() {
        List<Scenario> seed = CorpusRunner.loadCorpus(CorpusRunner.ACCESS_CORPUS);

        Path legacyData = FileUtil.resolveRes("layout-diff-migration-source");
        Path migratedData = FileUtil.resolveRes("layout-diff-migration-destination");
        FileUtil.deleteDir(legacyData);
        FileUtil.deleteDir(migratedData);

        Seeded seeded = seedOnLegacyLayout(legacyData, seed);
        Map<String, String> variables = seeded.variables();

        Path legacyRoot = legacyData.resolve(BLOB_ROOT);
        Path migratedRoot = migratedData.resolve(BLOB_ROOT);
        int copied = BucketCopier.copy(legacyRoot, migratedRoot, TENANT, TYPE_FOLDERS);
        assertTrue(copied > 0, "nothing was copied, so nothing below verifies anything");

        BucketVerifier.Result result = BucketVerifier.verify(legacyRoot, migratedRoot, TENANT, TYPE_FOLDERS);
        if (!result.clean()) {
            fail("Migrated bucket did not verify (" + result.sourceObjects() + " source objects, "
                    + result.destinationObjects() + " destination):\n  " + String.join("\n  ", result.problems()));
        }

        readBackOnTenantRootedLayout(migratedData, seeded);
    }

    /**
     * Writes a representative bucket on the legacy layout and leaves it on disk.
     */
    private record Seeded(Map<String, String> variables, Map<String, String> readBack) {
    }

    private static Seeded seedOnLegacyLayout(Path dataDir, List<Scenario> seed) throws Exception {
        try (DialInstance instance =
                     new DialInstance("migration-source", new JsonObject().put("tenantRooted", false),
                             SOURCE_REDIS_PORT, dataDir)) {
            CorpusRunner.Run run = CorpusRunner.replay(instance, seed);
            // What the resources read back as before anything moved. The question is whether migration
            // changes that, not whether every read is a 200 — some fixtures are legitimately not readable,
            // and asserting 200 outright would make the suite fail for reasons that have nothing to do with
            // the layout.
            Map<String, String> readBack = readAll(instance, run.variables());
            // Writes land in Redis first and are flushed behind the request; copying before that would copy a
            // tree that is missing whatever had not been written out yet.
            Thread.sleep(5000);
            return new Seeded(run.variables(), readBack);
        } finally {
            StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
        }
    }

    private static final List<String> READ_BACK = List.of(
            "conversations/${bucket1}/access/own",
            "conversations/${bucket1}/access/shared",
            "conversations/${bucket1}/access/writable",
            "conversations/${bucket1}/access/publishable",
            "applications/${bucket1}/access/app",
            "files/${bucket1}/appdata/testapp/data.txt",
            "conversations/public/access/published");

    private static Map<String, String> readAll(DialInstance instance, Map<String, String> variables) {
        Map<String, String> results = new LinkedHashMap<>();
        for (String url : READ_BACK) {
            RecordedResponse response = instance.send(HttpMethod.GET.name(), "/v1/" + resolve(url, variables),
                    null, null, Map.of("api-key", "proxyKey1"), null);
            results.put(url, response.status() + " " + response.body());
        }
        return results;
    }

    /**
     * Boots a tenant-rooted core onto the migrated tree, with an empty cache, and reads the seeded resources
     * back through the API. Inventory says the bytes arrived; only this says they can still be served.
     */
    private static void readBackOnTenantRootedLayout(Path migratedData, Seeded seeded) {
        Map<String, String> variables = seeded.variables();
        try (DialInstance instance = new DialInstance("migration-destination", new JsonObject()
                .put("tenantRooted", true)
                .put("defaultTenant", TENANT), MIGRATED_REDIS_PORT, migratedData)) {

            assertEquals(variables.get("bucket1"), instance.bucket("proxyKey1"),
                    "the migrated core resolves a different bucket, so nothing below addresses the same data");

            Map<String, String> after = readAll(instance, variables);
            assertTrue(after.values().stream().anyMatch(result -> result.startsWith("200")),
                    () -> "nothing at all was readable after migration, so this proves nothing: " + after);

            List<String> changed = READ_BACK.stream()
                    .filter(url -> !seeded.readBack().get(url).equals(after.get(url)))
                    .map(url -> "  " + url + "\n    before migration: " + seeded.readBack().get(url)
                            + "\n    after migration:  " + after.get(url))
                    .toList();
            if (!changed.isEmpty()) {
                fail("Migration changed what these resources read back as:\n" + String.join("\n", changed));
            }

            // The share and the publication have to survive too: they are the state that makes the data
            // usable rather than merely present.
            RecordedResponse shared = instance.send("POST", "/v1/ops/resource/share/list", null,
                    "{\"resourceTypes\":[\"CONVERSATION\"],\"with\":\"me\"}",
                    Map.of("api-key", "proxyKey2"), null);
            assertEquals(200, shared.status(), () -> "share listing failed after migration: " + shared.body());
            assertTrue(shared.body().contains("access/shared"),
                    () -> "the share did not survive migration: " + shared.body());

            RecordedResponse publicRead = instance.send(HttpMethod.GET.name(),
                    "/v1/conversations/public/access/published", null, null,
                    Map.of("api-key", "proxyKey2"), null);
            assertEquals(200, publicRead.status(),
                    () -> "published resource is not readable after migration: " + publicRead.body());
        } finally {
            StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
        }
    }

    private static String resolve(String template, Map<String, String> variables) {
        String resolved = template;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            resolved = resolved.replace("${" + variable.getKey() + "}", variable.getValue());
        }
        return resolved;
    }
}

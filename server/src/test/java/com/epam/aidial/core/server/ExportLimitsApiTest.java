package com.epam.aidial.core.server;

import com.epam.aidial.core.server.ExportApiSupport.Reply;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code adminExport} limits through the real server, with small values: too many roots is a status before the response starts, every
 * other limit ends the export after it started. The locks are free afterwards.
 */
public class ExportLimitsApiTest extends ResourceBaseTest {

    private static final String SMALL = ExportApiSupport.rootsRequest("applications/platform/small");

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("adminExport", new JsonObject()
                .put("maxRoots", 2).put("maxNodes", 5).put("maxDepth", 3).put("maxEdges", 6).put("maxResourceMiB", 1));
    }

    private void store(String name, String json) {
        ExportApiSupport.storeJson(dial, "platform/applications/" + name, json);
    }

    private void storeWithDependencies(String name, List<String> dependencies) {
        List<String> urls = new ArrayList<>();
        dependencies.forEach(dependency -> urls.add("\"applications/platform/" + dependency + "\""));
        store(name, "{\"endpoint\":\"http://" + name + ".invalid\",\"dependencies\":[" + String.join(",", urls) + "]}");
    }

    private void assertLimitEndsTheExportAndTheNextExportRuns(String root) throws Exception {
        ExportApiSupport.assertExportFails(serverPort, ExportApiSupport.rootsRequest("applications/platform/" + root));

        ExportApiSupport.awaitLocksReleased(dial.getProxy().getLockService());
        store("small", "{\"endpoint\":\"http://small.invalid\"}");
        assertEquals(200, ExportApiSupport.post(serverPort, SMALL).status());
    }

    @Test
    void testMoreRootsThanTheLimitIsBadRequestBeforeTheResponseStarts() throws Exception {
        store("a", "{\"endpoint\":\"http://a.invalid\"}");

        Reply reply = ExportApiSupport.post(serverPort, ExportApiSupport.rootsRequest(
                "applications/platform/a", "applications/platform/b", "applications/platform/c"));

        assertEquals(400, reply.status());
    }

    @Test
    void testMoreResourcesThanTheLimitEndTheExport() throws Exception {
        storeWithDependencies("wide", List.of("c1", "c2", "c3", "c4", "c5"));
        for (int i = 1; i <= 5; i++) {
            storeWithDependencies("c" + i, List.of());
        }

        assertLimitEndsTheExportAndTheNextExportRuns("wide");
    }

    @Test
    void testLongerChainThanTheLimitEndsTheExport() throws Exception {
        storeWithDependencies("n0", List.of("n1"));
        storeWithDependencies("n1", List.of("n2"));
        storeWithDependencies("n2", List.of("n3"));
        storeWithDependencies("n3", List.of("n4"));
        storeWithDependencies("n4", List.of());

        assertLimitEndsTheExportAndTheNextExportRuns("n0");
    }

    @Test
    void testMoreReferencesThanTheLimitEndTheExport() throws Exception {
        storeWithDependencies("many", List.of("r1", "r2", "r3", "r4", "r5", "r6", "r7"));

        assertLimitEndsTheExportAndTheNextExportRuns("many");
    }

    @Test
    void testBodyLargerThanTheLimitEndsTheExport() throws Exception {
        store("big", "{\"endpoint\":\"http://big.invalid\",\"description\":\"" + "x".repeat(1024 * 1024 + 10) + "\"}");

        assertLimitEndsTheExportAndTheNextExportRuns("big");
    }
}

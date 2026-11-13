package com.epam.aidial.core.server;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The deadline of the selective export: an export that is still running when it passes is cut, and the bucket locks are released.
 * The deadline is 1 ms, so the export of a few hundred resources cannot end before it.
 */
public class ExportDeadlineApiTest extends ResourceBaseTest {

    private static final int DEPENDENCIES = 400;

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("adminExport", new JsonObject().put("deadline", 1));
    }

    @Test
    void testExportThatOutlastsTheDeadlineEndsAndReleasesTheBucketLocks() throws Exception {
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < DEPENDENCIES; i++) {
            ExportApiSupport.storeJson(dial, "platform/applications/dependency-" + i, "{\"endpoint\":\"http://dependency.invalid/chat\"}");
            urls.add("\"applications/platform/dependency-" + i + "\"");
        }
        ExportApiSupport.storeJson(dial, "platform/applications/root",
                "{\"endpoint\":\"http://root.invalid/chat\",\"dependencies\":[" + String.join(",", urls) + "]}");

        ExportApiSupport.assertExportFails(serverPort, ExportApiSupport.rootsRequest("applications/platform/root"));

        ExportApiSupport.awaitLocksReleased(dial.getProxy().getLockService());
    }
}

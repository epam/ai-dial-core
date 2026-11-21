package com.epam.aidial.core.server;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared-space listings with org units on, for callers who hold no role at any unit.
 */
public class OrgUnitListingApiTest extends ResourceBaseTest {

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("orgUnits", new JsonObject().put("enabled", true));
    }

    @Test
    void testAttachedFolderListsWhatItReads() {
        Response created = send(HttpMethod.POST, "/v1/ops/publication/create", null, """
                {"targetFolder": "public/Acme/", "resources": [], "rules": [{"source": "title", "function": "EQUAL", "targets": ["Manager"]}]}
                """, "authorization", "admin");
        assertEquals(200, created.status(), created.body());
        assertEquals(200, send(HttpMethod.POST, "/v1/ops/publication/approve", null,
                new JsonObject().put("url", new JsonObject(created.body()).getString("url")).encode(), "authorization", "admin").status());
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public", null, "{\"name\": \"Acme\"}", "authorization", "admin").status());
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/Acme/a.txt", null, "a", "authorization", "admin").status());

        // the app's caller doesn't match the folder's rule: its access comes from the attachment alone
        ApiKeyData key = new ApiKeyData();
        key.setExtractedClaims(createWorkloadClaims("svc"));
        key.setSourceDeployment("testapp");
        key.setTraceId("trace-id");
        key.getAttachedFolders().put("files/public/Acme/", new AutoSharedData(ResourceAccessType.READ_ONLY));
        apiKeyStore.assignPerRequestApiKey(key);
        String apiKey = key.getPerRequestKey();

        assertEquals("a", send(HttpMethod.GET, "/v1/files/public/Acme/a.txt", null, null, "api-key", apiKey).body());
        for (String query : new String[] {null, "recursive=true"}) {
            List<String> urls = urls(query, apiKey);
            assertTrue(urls.contains("files/public/Acme/a.txt"), query + " " + urls);
        }
    }

    private List<String> urls(String query, String apiKey) {
        Response response = send(HttpMethod.GET, "/v1/metadata/files/public/Acme/", query, null, "api-key", apiKey);
        assertEquals(200, response.status(), response.body());
        List<String> urls = new ArrayList<>();
        JsonArray items = new JsonObject(response.body()).getJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            urls.add(items.getJsonObject(i).getString("url"));
        }
        return urls;
    }
}

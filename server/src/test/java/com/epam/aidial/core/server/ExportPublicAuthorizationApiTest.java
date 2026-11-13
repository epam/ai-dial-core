package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import com.epam.aidial.core.server.ExportApiSupport.Logs;
import com.epam.aidial.core.server.ExportApiSupport.Reply;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Authorization of {@code public/} resources through the real server and the real {@code AccessService}: a public root and a public
 * Application dependency are authorized separately and followed, a dependency in a user bucket is not followed or read. The 403 without
 * explicit admin access is tested in {@code ExportSelectionApiTest}.
 *
 * <p>The test rules give the admin ALL access on {@code public/}, so a denial of a public dependency cannot be reached here;
 * {@code ExportStreamerTest} covers it.
 */
public class ExportPublicAuthorizationApiTest extends ResourceBaseTest {

    private static final String ROOT = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/public/root__1.0.0\"}]}";

    @BeforeEach
    void storeTheScenario() {
        String userBucket = ProxyUtil.convertToObject(send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "user").body(), Map.class)
                .get("bucket").toString();
        store("public/applications/root__1.0.0", """
                {"endpoint":"http://root.invalid/chat","dependencies":[
                  "applications/public/dep__1.0.0",
                  "toolsets/public/ts",
                  "applications/%s/private-app"]}
                """.formatted(userBucket));
        store("public/applications/dep__1.0.0", "{\"endpoint\":\"http://dep.invalid/chat\"}");
        store("public/toolsets/ts", "{\"endpoint\":\"http://ts.invalid\",\"transport\":\"HTTP\",\"auth_settings\":{"
                + "\"authentication_type\":\"OAUTH\",\"client_id\":\"c\",\"client_secret\":\"PUBLIC-TOOLSET-SECRET\","
                + "\"code_verifier\":\"PUBLIC-TOOLSET-VERIFIER\",\"token_endpoint\":\"https://a.invalid/t\"}}");
    }

    private void store(String blobPath, String json) {
        ExportApiSupport.storeJson(dial, blobPath, json);
    }

    @Test
    void testPublicRootPublicApplicationAndPublicToolSetAreFollowedAndTheUserBucketOneIsNot() throws Exception {
        Reply reply;
        List<String> warnings;
        try (Logs logs = new Logs()) {
            reply = ExportApiSupport.post(serverPort, ROOT);
            warnings = logs.lines(Level.WARN, "Export: a reference is not exported");
        }

        assertEquals(200, reply.status());
        assertEquals("application/zip", reply.contentType());
        Map<String, byte[]> entries = reply.entries();

        assertEquals(List.of("public/applications/root__1.0.0", "public/applications/dep__1.0.0", "public/toolsets/ts"),
                ExportApiSupport.resourceNames(entries));
        assertEquals(4, entries.size(), "the manifest, the two Applications and the ToolSet");

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("resource=application:public:root__1.0.0 pointer=/dependencies/2 reason=unsupported"), warnings.get(0));

        JsonNode toolset = ProxyUtil.MAPPER.readTree(entries.get("public/toolsets/ts"));
        assertEquals("http://ts.invalid", toolset.get("endpoint").asText());
        entries.forEach((name, body) -> {
            String text = new String(body, StandardCharsets.UTF_8);
            assertFalse(text.contains("PUBLIC-TOOLSET-SECRET") || text.contains("PUBLIC-TOOLSET-VERIFIER"), name);
        });
    }
}

package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A skill keeps its files in the folder of its own name, so it never takes a unit's folder.
 */
public class OrgUnitSkillApiTest extends ResourceBaseTest {

    private static final String MANIFEST = """
            ---
            name: Skill
            description: Does something
            version: 1.0.0
            ---
            # Skill
            """;

    private String bucket;

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("orgUnits", new JsonObject().put("enabled", true));
    }

    @BeforeEach
    void units() {
        Response created = send(HttpMethod.POST, "/v1/ops/publication/create", null, """
                {"targetFolder": "public/Acme/", "resources": [], "rules": [{"source": "title", "function": "EQUAL", "targets": ["Manager"]}]}
                """, "authorization", "admin");
        assertEquals(200, created.status(), created.body());
        assertEquals(200, send(HttpMethod.POST, "/v1/ops/publication/approve", null,
                new JsonObject().put("url", new JsonObject(created.body()).getString("url")).encode(), "authorization", "admin").status());
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public", null, "{\"name\": \"Acme\"}", "authorization", "admin").status());
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public/Acme", null, "{\"name\": \"Risk\"}", "authorization", "admin").status());
        bucket = new JsonObject(send(HttpMethod.GET, "/v1/bucket", null, null, "authorization", "admin").body()).getString("bucket");
        assertEquals(200, uploadSkill(bucket + "/mine").status());
    }

    @Test
    void testSkillsCantTakeUnitFolders() {
        Response created = uploadSkill("public/Acme/Risk");
        assertEquals(409, created.status(), created.body());
        assertEquals(404, send(HttpMethod.GET, "/v2/metadata/skills/public/Acme/", null, null, "authorization", "admin").status(),
                "a refused skill leaves no grouping folder behind");
        assertEquals(200, uploadSkill("public/Acme/acme-skill").status());
        Response copied = send(HttpMethod.POST, "/v1/ops/resource/copy", null, new JsonObject()
                .put("sourceUrl", "skills/public/Acme/acme-skill").put("destinationUrl", "skills/public/Acme/Risk").encode(),
                "authorization", "admin");
        assertEquals(409, copied.status(), copied.body());
        assertEquals(200, uploadSkill("public/Acme/Risk/risk-skill").status(), "the unit's own skills are fine");
    }

    @Test
    void testPublishingSkillsOntoUnitFoldersIsRefusedBeforeApproval() {
        assertEquals(409, publish("skills/public/Acme/Risk").status());

        Response pending = publish("skills/public/Acme/Later");
        assertEquals(200, pending.status(), pending.body());
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public/Acme", null, "{\"name\": \"Later\"}", "authorization", "admin").status());
        String url = new JsonObject(pending.body()).getString("url");
        Response approved = send(HttpMethod.POST, "/v1/ops/publication/approve", null, new JsonObject().put("url", url).encode(), "authorization", "admin");
        assertEquals(409, approved.status(), approved.body());
        Response stored = send(HttpMethod.POST, "/v1/ops/publication/get", null, new JsonObject().put("url", url).encode(), "authorization", "admin");
        assertEquals("PENDING", new JsonObject(stored.body()).getString("status"));
    }

    private Response publish(String targetUrl) {
        return send(HttpMethod.POST, "/v1/ops/publication/create", null, new JsonObject().put("targetFolder", "public/Acme/")
                .put("resources", new JsonArray().add(new JsonObject().put("action", "ADD").put("sourceUrl", "skills/" + bucket + "/mine")
                        .put("targetUrl", targetUrl))).encode(), "authorization", "admin");
    }

    @SneakyThrows
    private Response uploadSkill(String path) {
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.PUT.name(), URI.create("http://127.0.0.1:" + serverPort + "/v2/skills/" + path));
        request.setHeader("authorization", "admin");
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.LEGACY);
        builder.setCharset(StandardCharsets.UTF_8);
        builder.addBinaryBody("SKILL.md", MANIFEST.getBytes(StandardCharsets.UTF_8), ContentType.DEFAULT_BINARY, "SKILL.md");
        request.setEntity(builder.build());
        return client.execute(request, ResourceBaseTest::toResponse);
    }
}

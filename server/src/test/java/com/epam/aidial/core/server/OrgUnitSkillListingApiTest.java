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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A grouping level of the skills tree named without its trailing slash is listed as its folder is: by the roles of
 * the units it holds.
 */
public class OrgUnitSkillListingApiTest extends ResourceBaseTest {

    private static final String MANIFEST = """
            ---
            name: Skill
            description: Does something
            version: 1.0.0
            ---
            # Skill
            """;

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("orgUnits", new JsonObject().put("enabled", true));
    }

    @BeforeEach
    void units() {
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public", null, "{\"name\": \"Acme\"}", "authorization", "admin").status());
        assertEquals(200, send(HttpMethod.POST, "/v1/org-units/public/Acme", null, "{\"name\": \"Risk\"}", "authorization", "admin").status());
        assertEquals(200, uploadSkill("public/Acme/acme-skill").status());
        assertEquals(200, uploadSkill("public/Acme/Risk/risk-skill").status());
        Response assigned = send(HttpMethod.POST, "/v1/org-units/public/Acme/role-assignments", null,
                "{\"roleName\": \"member\", \"subject\": {\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"bob\"]}}",
                "authorization", "admin");
        assertEquals(200, assigned.status(), assigned.body());
    }

    @Test
    void testGroupingLevelWithoutSlashIsFilteredAsItsFolder() {
        for (String path : List.of("public/Acme", "public/Acme/")) {
            assertEquals(List.of("skills/public/Acme/Risk/"), urls(path, "azp:bob"), path);
            assertEquals(List.of(), urls(path, "azp:nobody"), path);
        }
    }

    @Test
    void testRoleAtChildUnitListsOnlyItsOwnSkills() {
        // a role at Risk reads none of Acme's content through Risk's folder
        Response assigned = send(HttpMethod.POST, "/v1/org-units/public/Acme/Risk/role-assignments", null, "{\"roleName\": \"viewer\", "
                + "\"subject\": {\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"bob\"]}}",
                "authorization", "admin");
        assertEquals(200, assigned.status(), assigned.body());
        assertEquals(List.of("skills/public/Acme/Risk/risk-skill/"), urls("public/Acme/Risk/", "azp:bob"));
    }

    @Test
    void testRolesReachProjectsInTheirUnit() {
        assertEquals(200, upload("projects", "public/Acme/Risk/plan", "README.md").status());
        Response assigned = send(HttpMethod.POST, "/v1/org-units/public/Acme/Risk/role-assignments", null, "{\"roleName\": \"viewer\", "
                + "\"subject\": {\"type\": \"claimsPredicate\", \"source\": \"azp\", \"function\": \"EQUAL\", \"targets\": [\"bob\"]}}",
                "authorization", "admin");
        assertEquals(200, assigned.status(), assigned.body());

        String file = "/v2/projects/public/Acme/Risk/plan/files/README.md";
        assertEquals(200, send(HttpMethod.GET, file, null, null, "authorization", "azp:bob").status());
        assertEquals(403, send(HttpMethod.GET, file, null, null, "authorization", "azp:nobody").status());
    }

    @Test
    void testSkillsMoveInsideTheirUnit() {
        Response moved = send(HttpMethod.POST, "/v1/ops/resource/move", null, new JsonObject()
                .put("sourceUrl", "skills/public/Acme/acme-skill").put("destinationUrl", "skills/public/Acme/acme-moved").encode(),
                "authorization", "admin");
        assertEquals(200, moved.status(), moved.body());
        assertEquals(200, send(HttpMethod.GET, "/v2/skills/public/Acme/acme-moved/files/SKILL.md", null, null, "authorization", "admin").status());
        assertEquals(200, uploadSkill("public/Acme/after-move").status(), "the shared space's lock is free again");
    }

    @Test
    void testDotSegmentsAreRefused() {
        assertEquals(400, send(HttpMethod.GET, "/v2/skills/public/Acme/%2E/Risk/risk-skill/files/SKILL.md", null, null,
                "authorization", "admin").status());
        assertEquals(400, send(HttpMethod.GET, "/v2/metadata/skills/public/Acme/acme-skill/files/%2E%2E/%2E%2E/Risk", null, null,
                "authorization", "admin").status());
        assertEquals(400, send(HttpMethod.GET, "/v1/metadata/files/public/Acme/%2E/Risk/", null, null, "authorization", "admin").status());
    }

    private List<String> urls(String path, String caller) {
        Response response = send(HttpMethod.GET, "/v2/metadata/skills/" + path, "recursive=true", null, "authorization", caller);
        if (response.status() == 403) {
            return List.of();
        }
        assertEquals(200, response.status(), response.body());
        List<String> urls = new ArrayList<>();
        JsonArray items = new JsonObject(response.body()).getJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            urls.add(items.getJsonObject(i).getString("url"));
        }
        return urls.stream().sorted().toList();
    }

    private Response uploadSkill(String path) {
        return upload("skills", path, "SKILL.md");
    }

    @SneakyThrows
    private Response upload(String type, String path, String manifest) {
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.PUT.name(), URI.create("http://127.0.0.1:" + serverPort + "/v2/" + type + "/" + path));
        request.setHeader("authorization", "admin");
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.LEGACY);
        builder.setCharset(StandardCharsets.UTF_8);
        builder.addBinaryBody(manifest, MANIFEST.getBytes(StandardCharsets.UTF_8), ContentType.DEFAULT_BINARY, manifest);
        request.setEntity(builder.build());
        return client.execute(request, ResourceBaseTest::toResponse);
    }
}

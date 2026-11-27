package com.epam.aidial.core.server;

import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.service.ResourceService;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private BlobStorage originalStorage;

    private void installFilesystemRetries() {
        if (File.separatorChar != '\\') {
            return;
        }
        originalStorage = dial.getProxy().getStorage();
        BlobStorage storage = Mockito.spy(originalStorage);
        Mockito.doAnswer(invocation -> FileUtil.retryFilesystemCall(() -> callRealStorage(invocation))).when(storage)
                .store(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(byte[].class));
        Mockito.doAnswer(invocation -> FileUtil.retryFilesystemCall(() -> callRealStorage(invocation))).when(storage)
                .copy(Mockito.anyString(), Mockito.anyString(), Mockito.any());
        Mockito.doAnswer(invocation -> FileUtil.retryFilesystemCall(() -> {
            callRealStorage(invocation);
            String path = invocation.getArgument(0);
            if (originalStorage.exists(path)) {
                throw new FileSystemException(path, null, "Filesystem provider left the deleted blob in place");
            }
            return null;
        })).when(storage).delete(Mockito.anyString());
        replaceStorage(storage);
    }

    @AfterEach
    void restoreStorage() {
        if (originalStorage != null) {
            replaceStorage(originalStorage);
        }
    }

    private void replaceStorage(BlobStorage storage) {
        replaceStorageField(dial.getProxy(), Proxy.class, "storage", storage);
        replaceStorageField(dial.getProxy().getResourceService(), ResourceService.class, "blobStore", storage);
        replaceStorageField(dial.getProxy().getComplexResourceService(), ComplexResourceService.class, "blobStorage", storage);
    }

    @SneakyThrows
    private static void replaceStorageField(Object owner, Class<?> type, String name, BlobStorage storage) {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, storage);
    }

    @SneakyThrows
    private static Object callRealStorage(InvocationOnMock invocation) {
        return invocation.callRealMethod();
    }

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("orgUnits", new JsonObject().put("enabled", true));
    }

    @BeforeEach
    void units() {
        installFilesystemRetries();
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
        assertEquals(409, uploadSkill("public/Acme/risk").status(), "unit names compare ignoring case");
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

    @Test
    void testDeletedSkillsDontKeepUnitsFromBeingRemoved() {
        assertEquals(200, uploadSkill("public/Acme/Risk/Team/risk-skill").status());
        Response refused = send(HttpMethod.DELETE, "/v1/org-units/public/Acme/Risk", null, null, "authorization", "admin");
        assertEquals(409, refused.status(), refused.body());
        assertTrue(refused.body().contains("skills/public/Acme/Risk/Team/risk-skill"), refused.body());

        assertEquals(200, send(HttpMethod.DELETE, "/v2/skills/public/Acme/Risk/Team/risk-skill", null, null, "authorization", "admin").status());
        Response removed = send(HttpMethod.DELETE, "/v1/org-units/public/Acme/Risk", null, null, "authorization", "admin");
        assertEquals(200, removed.status(), removed.body());
        Response left = send(HttpMethod.GET, "/v2/metadata/skills/public/Acme/", "recursive=true", null, "authorization", "admin");
        assertEquals(200, left.status(), left.body());
        assertFalse(left.body().contains("Risk"), "the unit's grouping folders go with it: " + left.body());
    }

    @Test
    void testComplexFileMoveAcrossUnitOwners() {
        for (String path : List.of("public/loose", "public/Acme/acme-skill", "public/Acme/Risk/risk-skill")) {
            assertEquals(200, uploadSkill(path, true).status());
        }
        assertEquals(200, upload(HttpMethod.PUT, "/v1/files/public/plain.txt", null, "plain", "authorization", "admin").status());
        String loose = "skills/public/loose/files/a.txt";
        String acme = "skills/public/Acme/acme-skill/files/a.txt";
        String risk = "skills/public/Acme/Risk/risk-skill/files/a.txt";
        for (String[] pair : List.of(new String[]{loose, acme}, new String[]{acme, loose}, new String[]{acme, risk},
                new String[]{risk, acme}, new String[]{"files/public/plain.txt", acme}, new String[]{acme, "files/public/plain.txt"})) {
            String destination = pair[1].replace("a.txt", "copied.txt");
            String request = new JsonObject().put("sourceUrl", pair[0]).put("destinationUrl", destination).put("overwrite", true).encode();
            String destinationRoute = (destination.startsWith("files/") ? "/v1/" : "/v2/") + destination;
            Response previousTarget = send(HttpMethod.GET, destinationRoute, null, null, "authorization", "admin");
            Response refused = send(HttpMethod.POST, "/v1/ops/resource/move", null, request, "authorization", "admin");
            assertEquals(409, refused.status(), refused.body());
            assertTrue(refused.body().contains("org unit"), refused.body());
            Response unchangedTarget = send(HttpMethod.GET, destinationRoute, null, null, "authorization", "admin");
            assertEquals(previousTarget.status(), unchangedTarget.status());
            assertEquals(previousTarget.body(), unchangedTarget.body());
            Response original = send(HttpMethod.GET, (pair[0].startsWith("files/") ? "/v1/" : "/v2/") + pair[0], null, null,
                    "authorization", "admin");
            assertEquals(200, original.status(), original.body());
            Response copied = send(HttpMethod.POST, "/v1/ops/resource/copy", null, request, "authorization", "admin");
            assertEquals(200, copied.status(), copied.body());
            Response target = send(HttpMethod.GET, (destination.startsWith("files/") ? "/v1/" : "/v2/") + destination, null, null,
                    "authorization", "admin");
            assertEquals(200, target.status(), target.body());
            assertEquals(original.body(), target.body());
        }
        String withinUnit = new JsonObject().put("sourceUrl", acme).put("destinationUrl", acme.replace("a.txt", "renamed.txt")).encode();
        assertEquals(200, send(HttpMethod.POST, "/v1/ops/resource/move", null, withinUnit, "authorization", "admin").status());
        assertEquals(404, send(HttpMethod.GET, "/v2/" + acme, null, null, "authorization", "admin").status());
    }

    private Response publish(String targetUrl) {
        return send(HttpMethod.POST, "/v1/ops/publication/create", null, new JsonObject().put("targetFolder", "public/Acme/")
                .put("resources", new JsonArray().add(new JsonObject().put("action", "ADD").put("sourceUrl", "skills/" + bucket + "/mine")
                        .put("targetUrl", targetUrl))).encode(), "authorization", "admin");
    }

    private Response uploadSkill(String path) {
        return uploadSkill(path, false);
    }

    @SneakyThrows
    private Response uploadSkill(String path, boolean extraFile) {
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.PUT.name(), URI.create("http://127.0.0.1:" + serverPort + "/v2/skills/" + path));
        request.setHeader("authorization", "admin");
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.LEGACY);
        builder.setCharset(StandardCharsets.UTF_8);
        builder.addBinaryBody("SKILL.md", MANIFEST.getBytes(StandardCharsets.UTF_8), ContentType.DEFAULT_BINARY, "SKILL.md");
        if (extraFile) {
            builder.addBinaryBody("a.txt", "skill file".getBytes(StandardCharsets.UTF_8), ContentType.TEXT_PLAIN, "a.txt");
        }
        request.setEntity(builder.build());
        return client.execute(request, ResourceBaseTest::toResponse);
    }
}

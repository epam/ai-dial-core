package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SkillResourceApiTest extends ResourceBaseTest {

    private static final String MARKER_NAME = ".dial-resource";

    private static final String VALID_MANIFEST = """
            ---
            name: My Skill
            description: Does something useful
            version: 1.0.0
            ---
            # My Skill

            Body of the skill.
            """;

    @Test
    void testCreateAndDownloadRoundTrip() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("scripts/run.sh", "echo hi".getBytes(StandardCharsets.UTF_8));

        Response put = uploadSkill("/my-skill", files);
        verify(put, 200);
        String etag = put.headers().get("etag");
        assertNotNull(etag);

        BinaryResponse zip = downloadSkill("/my-skill");
        assertEquals(200, zip.status());
        assertTrue(zip.headers().get("content-type").startsWith("application/zip"));
        assertEquals(etag, zip.headers().get("etag"));

        Map<String, byte[]> unpacked = unzip(zip.body());
        assertEquals(files.keySet(), unpacked.keySet());
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            assertEquals(new String(entry.getValue(), StandardCharsets.UTF_8),
                    new String(unpacked.get(entry.getKey()), StandardCharsets.UTF_8));
        }
        // the marker must not leak into the archive
        assertFalse(unpacked.containsKey(MARKER_NAME));
    }

    @Test
    void testRejectMissingManifest() {
        Map<String, byte[]> files = Map.of("data.txt", "x".getBytes(StandardCharsets.UTF_8));

        Response put = uploadSkill("/no-manifest", files);
        verify(put, 400);

        // nothing observable was written
        assertEquals(404, downloadSkill("/no-manifest").status());
    }

    @Test
    void testRejectIncompleteFrontmatter() {
        String manifest = """
                ---
                name: Only Name
                ---
                """;
        Map<String, byte[]> files = Map.of("SKILL.md", manifest.getBytes(StandardCharsets.UTF_8));

        Response put = uploadSkill("/bad-frontmatter", files);
        verify(put, 400);
        assertEquals(404, downloadSkill("/bad-frontmatter").status());
    }

    @Test
    void testRejectUnparseableFrontmatter() {
        Map<String, byte[]> files = Map.of("SKILL.md", "# no frontmatter here".getBytes(StandardCharsets.UTF_8));

        verify(uploadSkill("/no-frontmatter", files), 400);
    }

    @Test
    void testIfMatch() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));

        Response first = uploadSkill("/versioned", files);
        verify(first, 200);
        String etag = first.headers().get("etag");

        // wrong If-Match -> 412
        verify(uploadSkill("/versioned", files, "if-match", "\"wrong\""), 412);

        // matching If-Match -> 200
        verify(uploadSkill("/versioned", files, "if-match", etag), 200);
    }

    @Test
    void testRejectReservedResourceName() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/v", files), 400);
    }

    @Test
    void testRejectReservedPartName() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put(MARKER_NAME, "{}".getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/reserved-part", files), 400);
    }

    @Test
    void testNestedPathRejectedByRouting() {
        // The v2 route only matches a single root-level path segment.
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        assertNotEquals(200, uploadSkill("/group/nested", files).status());
    }

    @Test
    void testTrailingSlashRejectedByRouting() {
        // The v2 route addresses a resource by name without a trailing slash.
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        assertNotEquals(200, uploadSkill("/trailing/", files).status());
    }

    @Test
    void testDelete() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));

        verify(uploadSkill("/to-delete", files), 200);
        verify(deleteSkill("/to-delete"), 200);
        assertEquals(404, downloadSkill("/to-delete").status());
    }

    @Test
    void testDeleteIfMatch() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));

        Response put = uploadSkill("/delete-etag", files);
        verify(put, 200);
        String etag = put.headers().get("etag");

        // wrong If-Match -> 412, resource still live
        verify(deleteSkill("/delete-etag", "if-match", "\"wrong\""), 412);
        assertEquals(200, downloadSkill("/delete-etag").status());

        // correct If-Match -> 200, resource gone
        verify(deleteSkill("/delete-etag", "if-match", etag), 200);
        assertEquals(404, downloadSkill("/delete-etag").status());
    }

    @Test
    void testDeleteNonExistent() {
        verify(deleteSkill("/never-existed"), 404);
    }

    @Test
    void testInvisibleToV1FilesApi() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/hidden", files), 200);

        // v1 files API stores under a different blob prefix and cannot see the skill
        Response viaFiles = send(HttpMethod.GET, "/v1/files/" + bucket + "/hidden/.dial-resource", null, "");
        verify(viaFiles, 404);
    }

    @Test
    void testPutNewFile() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        Response created = uploadSkill("/file-add", files);
        verify(created, 200);
        String before = created.headers().get("etag");

        Response put = putSkillFile("/file-add", "docs/readme.md", "hello".getBytes(StandardCharsets.UTF_8));
        verify(put, 200);
        String after = put.headers().get("etag");
        assertNotNull(after);
        assertNotEquals(before, after);

        BinaryResponse got = getSkillFile("/file-add", "docs/readme.md");
        assertEquals(200, got.status());
        assertEquals("hello", new String(got.body(), StandardCharsets.UTF_8));

        // the whole-skill archive now contains the added file
        Map<String, byte[]> unpacked = unzip(downloadSkill("/file-add").body());
        assertTrue(unpacked.containsKey("docs/readme.md"));
    }

    @Test
    void testReplaceFile() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("data.txt", "old".getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/file-replace", files), 200);

        verify(putSkillFile("/file-replace", "data.txt", "new".getBytes(StandardCharsets.UTF_8)), 200);
        assertEquals("new", new String(getSkillFile("/file-replace", "data.txt").body(), StandardCharsets.UTF_8));
    }

    @Test
    void testDeleteFile() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("scripts/run.sh", "echo".getBytes(StandardCharsets.UTF_8));
        Response created = uploadSkill("/file-del", files);
        verify(created, 200);
        String before = created.headers().get("etag");

        Response del = deleteSkillFile("/file-del", "scripts/run.sh");
        verify(del, 200);
        assertNotEquals(before, del.headers().get("etag"));

        assertEquals(404, getSkillFile("/file-del", "scripts/run.sh").status());
        Map<String, byte[]> unpacked = unzip(downloadSkill("/file-del").body());
        assertFalse(unpacked.containsKey("scripts/run.sh"));
        assertTrue(unpacked.containsKey("SKILL.md"));
    }

    @Test
    void testDeleteManifestRejected() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        Response created = uploadSkill("/file-guard", files);
        verify(created, 200);
        String before = created.headers().get("etag");

        verify(deleteSkillFile("/file-guard", "SKILL.md"), 400);

        // the skill is unchanged
        BinaryResponse zip = downloadSkill("/file-guard");
        assertEquals(200, zip.status());
        assertEquals(before, zip.headers().get("etag"));
    }

    @Test
    void testEditManifestRefreshesContent() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        Response created = uploadSkill("/file-manifest", files);
        verify(created, 200);
        String before = created.headers().get("etag");

        String updated = """
                ---
                name: Renamed Skill
                description: New description
                version: 2.0.0
                ---
                # Renamed
                """;
        Response put = putSkillFile("/file-manifest", "SKILL.md", updated.getBytes(StandardCharsets.UTF_8));
        verify(put, 200);
        assertNotEquals(before, put.headers().get("etag"));
        assertEquals(updated, new String(getSkillFile("/file-manifest", "SKILL.md").body(), StandardCharsets.UTF_8));
    }

    @Test
    void testEditManifestInvalidFrontmatterRejected() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        Response created = uploadSkill("/file-manifest-bad", files);
        verify(created, 200);
        String before = created.headers().get("etag");

        // valid YAML but missing the mandatory description -> 400
        String bad = """
                ---
                name: Only Name
                ---
                """;
        verify(putSkillFile("/file-manifest-bad", "SKILL.md", bad.getBytes(StandardCharsets.UTF_8)), 400);

        // the skill is unchanged
        assertEquals(before, downloadSkill("/file-manifest-bad").headers().get("etag"));
        assertEquals(VALID_MANIFEST,
                new String(getSkillFile("/file-manifest-bad", "SKILL.md").body(), StandardCharsets.UTF_8));
    }

    @Test
    void testPutFileIfMatch() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        Response created = uploadSkill("/file-ifmatch", files);
        verify(created, 200);
        String etag = created.headers().get("etag");

        verify(putSkillFile("/file-ifmatch", "a.txt", "x".getBytes(StandardCharsets.UTF_8), "if-match", "\"wrong\""), 412);
        verify(putSkillFile("/file-ifmatch", "a.txt", "x".getBytes(StandardCharsets.UTF_8), "if-match", etag), 200);
    }

    @Test
    void testDeleteFileIfMatch() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("a.txt", "x".getBytes(StandardCharsets.UTF_8));
        Response created = uploadSkill("/file-del-ifmatch", files);
        verify(created, 200);
        String etag = created.headers().get("etag");

        verify(deleteSkillFile("/file-del-ifmatch", "a.txt", "if-match", "\"wrong\""), 412);
        verify(deleteSkillFile("/file-del-ifmatch", "a.txt", "if-match", etag), 200);
    }

    @Test
    void testSingleFileOnAbsentResource() {
        assertEquals(404, getSkillFile("/absent", "a.txt").status());
        verify(putSkillFile("/absent", "a.txt", "x".getBytes(StandardCharsets.UTF_8)), 404);
        verify(deleteSkillFile("/absent", "a.txt"), 404);
    }

    @Test
    void testSingleFileOnDeletedResource() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/file-after-delete", files), 200);
        verify(deleteSkill("/file-after-delete"), 200);

        assertEquals(404, getSkillFile("/file-after-delete", "SKILL.md").status());
        verify(putSkillFile("/file-after-delete", "a.txt", "x".getBytes(StandardCharsets.UTF_8)), 404);
        verify(deleteSkillFile("/file-after-delete", "a.txt"), 404);
    }

    @SneakyThrows
    private Response uploadSkill(String skillPath, Map<String, byte[]> files, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/skills/" + bucket + skillPath;
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.PUT.name(), URI.create(uri));

        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
            request.setHeader("api-key", "proxyKey1");
        }

        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.LEGACY);
        builder.setCharset(StandardCharsets.UTF_8);
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            builder.addBinaryBody(entry.getKey(), entry.getValue(), ContentType.DEFAULT_BINARY, entry.getKey());
        }
        request.setEntity(builder.build());

        return client.execute(request, ResourceBaseTest::toResponse);
    }

    @SneakyThrows
    private Response deleteSkill(String skillPath, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/skills/" + bucket + skillPath;
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.DELETE.name(), URI.create(uri));

        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
            request.setHeader("api-key", "proxyKey1");
        }

        return client.execute(request, ResourceBaseTest::toResponse);
    }

    @SneakyThrows
    private BinaryResponse downloadSkill(String skillPath, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/skills/" + bucket + skillPath;
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.GET.name(), URI.create(uri));

        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
            request.setHeader("api-key", "proxyKey1");
        }

        return client.execute(request, response -> {
            int status = response.getCode();
            byte[] body = response.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(response.getEntity());
            Map<String, String> responseHeaders = new HashMap<>();
            for (Header header : response.getHeaders()) {
                responseHeaders.put(header.getName(), header.getValue());
            }
            return new BinaryResponse(status, body, responseHeaders);
        });
    }

    @SneakyThrows
    private Response putSkillFile(String skillPath, String filePath, byte[] content, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/skills/" + bucket + skillPath + "/files/" + filePath;
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.PUT.name(), URI.create(uri));

        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
            request.setHeader("api-key", "proxyKey1");
        }

        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.LEGACY);
        builder.setCharset(StandardCharsets.UTF_8);
        builder.addBinaryBody("file", content, ContentType.DEFAULT_BINARY, "file");
        request.setEntity(builder.build());

        return client.execute(request, ResourceBaseTest::toResponse);
    }

    @SneakyThrows
    private Response deleteSkillFile(String skillPath, String filePath, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/skills/" + bucket + skillPath + "/files/" + filePath;
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.DELETE.name(), URI.create(uri));

        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
            request.setHeader("api-key", "proxyKey1");
        }

        return client.execute(request, ResourceBaseTest::toResponse);
    }

    @SneakyThrows
    private BinaryResponse getSkillFile(String skillPath, String filePath, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/skills/" + bucket + skillPath + "/files/" + filePath;
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.GET.name(), URI.create(uri));

        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
            request.setHeader("api-key", "proxyKey1");
        }

        return client.execute(request, response -> {
            int status = response.getCode();
            byte[] body = response.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(response.getEntity());
            Map<String, String> responseHeaders = new HashMap<>();
            for (Header header : response.getHeaders()) {
                responseHeaders.put(header.getName(), header.getValue());
            }
            return new BinaryResponse(status, body, responseHeaders);
        });
    }

    @SneakyThrows
    private static Map<String, byte[]> unzip(byte[] archive) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                result.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return result;
    }

    private record BinaryResponse(int status, byte[] body, Map<String, String> headers) {
    }
}

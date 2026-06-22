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

        Response put = uploadSkill("/my-skill/", files);
        verify(put, 200);
        String etag = put.headers().get("etag");
        assertNotNull(etag);

        BinaryResponse zip = downloadSkill("/my-skill/");
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

        Response put = uploadSkill("/no-manifest/", files);
        verify(put, 400);

        // nothing observable was written
        assertEquals(404, downloadSkill("/no-manifest/").status());
    }

    @Test
    void testRejectIncompleteFrontmatter() {
        String manifest = """
                ---
                name: Only Name
                ---
                """;
        Map<String, byte[]> files = Map.of("SKILL.md", manifest.getBytes(StandardCharsets.UTF_8));

        Response put = uploadSkill("/bad-frontmatter/", files);
        verify(put, 400);
        assertEquals(404, downloadSkill("/bad-frontmatter/").status());
    }

    @Test
    void testRejectUnparseableFrontmatter() {
        Map<String, byte[]> files = Map.of("SKILL.md", "# no frontmatter here".getBytes(StandardCharsets.UTF_8));

        verify(uploadSkill("/no-frontmatter/", files), 400);
    }

    @Test
    void testIfMatch() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));

        Response first = uploadSkill("/versioned/", files);
        verify(first, 200);
        String etag = first.headers().get("etag");

        // wrong If-Match -> 412
        verify(uploadSkill("/versioned/", files, "if-match", "\"wrong\""), 412);

        // matching If-Match -> 200
        verify(uploadSkill("/versioned/", files, "if-match", etag), 200);
    }

    @Test
    void testRejectReservedResourceName() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/v/", files), 400);
    }

    @Test
    void testRejectReservedPartName() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put(MARKER_NAME, "{}".getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/reserved-part/", files), 400);
    }

    @Test
    void testNestedPathRejectedByRouting() {
        // The v2 route only matches a single root-level path segment.
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        assertNotEquals(200, uploadSkill("/group/nested/", files).status());
    }

    @Test
    void testInvisibleToV1FilesApi() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/hidden/", files), 200);

        // v1 files API stores under a different blob prefix and cannot see the skill
        Response viaFiles = send(HttpMethod.GET, "/v1/files/" + bucket + "/hidden/.dial-resource", null, "");
        verify(viaFiles, 404);
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

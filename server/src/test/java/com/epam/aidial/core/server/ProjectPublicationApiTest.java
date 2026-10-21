package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * Covers publishing a whole project (folder-as-resource) through the review -&gt; public flow: the copy at
 * each hop must be independently readable and byte-identical, and reject/withdraw must clean up the review
 * copy the same way other resource types do.
 */
class ProjectPublicationApiTest extends ResourceBaseTest {

    private static final String MANIFEST = """
            ---
            name: Publishable Project
            description: A project used to test the publication lifecycle
            version: 1.0.0
            ---
            # Publishable Project
            """;

    @Test
    void testPublishAndApproveProjectCopiesWholeTreeAtEachHop() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("README.md", MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("scripts/run.sh", "echo hi".getBytes(StandardCharsets.UTF_8));

        Response uploaded = uploadProject("/pub-project", files);
        verify(uploaded, 200);
        String sourceEtag = uploaded.headers().get("etag");

        Response create = operationRequest("/v1/ops/publication/create", """
                {
                  "name": "Publish project",
                  "targetFolder": "public/folder/",
                  "resources": [
                    {"action":"ADD","sourceUrl":"projects/%s/pub-project","targetUrl":"projects/public/folder/pub-project"}
                  ],
                  "rules": [{"source":"roles","function":"TRUE"}]
                }
                """.formatted(bucket));
        verify(create, 200);
        String reviewBucketSegment = reviewBucketSegment(create);

        // the review copy is a fresh, independent tree: readable and byte-identical to the source
        BinaryResponse review = downloadProject(reviewBucketSegment, "/pub-project");
        assertEquals(200, review.status());
        assertEquals(files.keySet(), unzip(review.body()).keySet());
        assertEquals(sourceEtag, review.headers().get("etag"));

        Response approve = operationRequest("/v1/ops/publication/approve", """
                {"url":"publications/%s/0123"}
                """.formatted(bucket), "authorization", "admin");
        verify(approve, 200);

        // approved: readable at the public location, byte-identical to the source...
        BinaryResponse published = downloadProject("public/folder", "/pub-project");
        assertEquals(200, published.status());
        Map<String, byte[]> publishedFiles = unzip(published.body());
        assertEquals(files.keySet(), publishedFiles.keySet());
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            assertEquals(new String(entry.getValue(), StandardCharsets.UTF_8),
                    new String(publishedFiles.get(entry.getKey()), StandardCharsets.UTF_8));
        }
        assertEquals(sourceEtag, published.headers().get("etag"));

        // ...the review copy is cleaned up...
        assertEquals(404, downloadProject(reviewBucketSegment, "/pub-project").status());

        // ...and the original source project is left untouched
        BinaryResponse source = downloadProject(bucket, "/pub-project");
        assertEquals(200, source.status());
        assertEquals(sourceEtag, source.headers().get("etag"));
    }

    @Test
    void testRejectProjectPublicationCleansUpReviewCopy() {
        Map<String, byte[]> files = Map.of("README.md", MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/reject-project", files), 200);

        Response create = operationRequest("/v1/ops/publication/create", """
                {
                  "name": "Reject project",
                  "targetFolder": "public/folder/",
                  "resources": [
                    {"action":"ADD","sourceUrl":"projects/%s/reject-project","targetUrl":"projects/public/folder/reject-project"}
                  ],
                  "rules": [{"source":"roles","function":"TRUE"}]
                }
                """.formatted(bucket));
        verify(create, 200);
        String reviewBucketSegment = reviewBucketSegment(create);

        assertEquals(200, downloadProject(reviewBucketSegment, "/reject-project").status());

        Response reject = operationRequest("/v1/ops/publication/reject", """
                {"url":"publications/%s/0123","comment":"no"}
                """.formatted(bucket), "authorization", "admin");
        verify(reject, 200);

        // rejection behaves like other resource types: the review copy is gone, nothing landed in public,
        // and the original source is untouched
        assertEquals(404, downloadProject(reviewBucketSegment, "/reject-project").status());
        assertEquals(404, downloadProject("public/folder", "/reject-project").status());
        assertEquals(200, downloadProject(bucket, "/reject-project").status());
    }

    @Test
    void testWithdrawPendingProjectPublicationCleansUpReviewCopy() {
        Map<String, byte[]> files = Map.of("README.md", MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/withdraw-project", files), 200);

        Response create = operationRequest("/v1/ops/publication/create", """
                {
                  "name": "Withdraw project",
                  "targetFolder": "public/folder/",
                  "resources": [
                    {"action":"ADD","sourceUrl":"projects/%s/withdraw-project","targetUrl":"projects/public/folder/withdraw-project"}
                  ],
                  "rules": [{"source":"roles","function":"TRUE"}]
                }
                """.formatted(bucket));
        verify(create, 200);
        String reviewBucketSegment = reviewBucketSegment(create);

        assertEquals(200, downloadProject(reviewBucketSegment, "/withdraw-project").status());

        Response withdraw = operationRequest("/v1/ops/publication/delete", """
                {"url":"publications/%s/0123"}
                """.formatted(bucket));
        verify(withdraw, 200);

        // withdrawing a still-pending publication behaves like other resource types: the review copy is
        // removed, and the original source is untouched
        assertEquals(404, downloadProject(reviewBucketSegment, "/withdraw-project").status());
        assertEquals(200, downloadProject(bucket, "/withdraw-project").status());
    }

    @SneakyThrows
    private static String reviewBucketSegment(Response create) {
        JsonNode publication = ProxyUtil.MAPPER.readTree(create.body());
        String reviewUrl = publication.get("resources").get(0).get("reviewUrl").asText();
        // "projects/{encryptedReviewBucket}/{name}" -> the encrypted bucket segment
        return reviewUrl.substring("projects/".length(), reviewUrl.lastIndexOf('/'));
    }

    @SneakyThrows
    private Response uploadProject(String projectPath, Map<String, byte[]> files) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/projects/" + bucket + projectPath;
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.PUT.name(), URI.create(uri));
        request.setHeader("api-key", "proxyKey1");

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
    private BinaryResponse downloadProject(String bucketSegment, String projectPath) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/projects/" + bucketSegment + projectPath;
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.GET.name(), URI.create(uri));
        request.setHeader("api-key", "proxyKey1");

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

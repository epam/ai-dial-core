package com.epam.aidial.core.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end MCP file-tool coverage (M.3.0). Exercises {@code dial_upload_file} (base64 +
 * source_url default-deny + max_bytes cap) and {@code dial_download_file} (bytes envelope,
 * image-content block, 404, max_bytes cap) against the real Core file controllers.
 */
class McpFileToolsTest extends ResourceBaseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void toolsListExposesNineTools() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode tools = McpTestSupport.callMcp(this, sessionId, McpTestSupport.toolsListEnvelope(), null)
                .get("result").get("tools");
        assertEquals(9, tools.size());
        Set<String> names = new HashSet<>();
        for (JsonNode tool : tools) {
            names.add(tool.get("name").asText());
        }
        assertTrue(names.contains("dial_upload_file"));
        assertTrue(names.contains("dial_download_file"));
    }

    @Test
    void uploadFileFromBase64ContentReturnsEtag() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        byte[] payload = "hello-mcp-upload".getBytes();
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "files/" + bucket + "/mcp-upload-1.txt")
                .put("content", Base64.getEncoder().encodeToString(payload))
                .put("content_type", "text/plain");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_upload_file", args, null);
        assertFalse(result.get("isError").asBoolean(), () -> "Body: " + result.toString());
        JsonNode body = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        assertTrue(body.get("uploaded").asBoolean());
        assertEquals("files/" + bucket + "/mcp-upload-1.txt", body.get("id").asText());
        assertEquals("text/plain", body.get("content_type").asText());
        assertEquals(payload.length, body.get("size").asInt());
        assertFalse(body.get("etag").isNull(), "Core returns ETag for file upload");

        Response meta = send(HttpMethod.GET, "/v1/metadata/files/" + bucket + "/mcp-upload-1.txt", null, "");
        assertEquals(200, meta.status(), "uploaded file must be discoverable via metadata GET");
    }

    @Test
    void uploadFileMissingContentAndSourceUrlReturnsRemediation() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode().put("id", "files/" + bucket + "/missing.txt");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_upload_file", args, null);
        assertTrue(result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("exactly one of"));
        assertTrue(text.contains("Neither was provided"));
    }

    @Test
    void uploadFileWithBothContentAndSourceUrlReturnsRemediation() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "files/" + bucket + "/conflict.txt")
                .put("content", Base64.getEncoder().encodeToString("hi".getBytes()))
                .put("source_url", "https://example.com/x");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_upload_file", args, null);
        assertTrue(result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("Both were provided"));
    }

    @Test
    void uploadFileWithSourceUrlDisabledByDefaultReturnsRemediation() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "files/" + bucket + "/from-url.txt")
                .put("source_url", "https://example.com/asset.png");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_upload_file", args, null);
        assertTrue(result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("mcp.upload.sourceUrl.enabled"));
    }

    @Test
    void uploadFileExceedsMaxBytesReturnsRemediation() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        byte[] payload = new byte[64];
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "files/" + bucket + "/too-big.txt")
                .put("content", Base64.getEncoder().encodeToString(payload))
                .put("max_bytes", 8);
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_upload_file", args, null);
        assertTrue(result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("exceeds max_bytes"));
    }

    @Test
    void uploadFileRejectsNonFileType() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "models/public/wrong")
                .put("content", Base64.getEncoder().encodeToString("hi".getBytes()));
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_upload_file", args, null);
        assertTrue(result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("only accepts type 'files'"));
        assertTrue(text.contains("dial_create_resource"));
    }

    @Test
    void downloadFileReturnsBytesEnvelope() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        Response uploaded = upload(HttpMethod.PUT, "/v1/files/" + bucket + "/mcp-download-1.txt", null,
                "download-payload");
        assertEquals(200, uploaded.status(), () -> "fixture upload failed: " + uploaded.body());

        ObjectNode args = MAPPER.createObjectNode().put("id", "files/" + bucket + "/mcp-download-1.txt");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_download_file", args, null);
        assertFalse(result.get("isError").asBoolean(), () -> "Body: " + result.toString());
        JsonNode body = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        assertTrue(body.get("downloaded").asBoolean());
        assertEquals("files/" + bucket + "/mcp-download-1.txt", body.get("id").asText());
        assertEquals("download-payload", new String(Base64.getDecoder().decode(body.get("content_base64").asText())));
        assertEquals("download-payload".length(), body.get("size").asInt());
        assertFalse(body.get("etag").isNull());
    }

    @Test
    void downloadFileImageFormatReturnsImageContent() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        byte[] pngBytes = new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 1};
        Response uploaded = uploadBinary("/v1/files/" + bucket + "/mcp-download.png", "image/png", pngBytes);
        assertEquals(200, uploaded.status(), () -> "image fixture upload failed: " + uploaded.body());

        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "files/" + bucket + "/mcp-download.png")
                .put("format", "image");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_download_file", args, null);
        assertFalse(result.get("isError").asBoolean(), () -> "Body: " + result.toString());
        JsonNode block = result.get("content").get(0);
        assertEquals("image", block.get("type").asText(), () -> "Expected image content block: " + block);
        assertEquals("image/png", block.get("mimeType").asText());
        assertEquals(Base64.getEncoder().encodeToString(pngBytes), block.get("data").asText());
    }

    @Test
    void downloadFileMissingReturns404() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode().put("id", "files/" + bucket + "/never-existed.txt");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_download_file", args, null);
        assertTrue(result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("HTTP 404"));
        assertTrue(text.contains("never-existed.txt"));
    }

    @Test
    void downloadFileExceedsMaxBytesReturnsRemediation() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        Response uploaded = upload(HttpMethod.PUT, "/v1/files/" + bucket + "/big.txt", null,
                "abcdefghijabcdefghij");
        assertEquals(200, uploaded.status());

        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "files/" + bucket + "/big.txt")
                .put("max_bytes", 5);
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_download_file", args, null);
        assertTrue(result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("exceeds max_bytes"));
        assertTrue(text.contains("dial_get_resource"));
    }

    private Response uploadBinary(String path, String contentType, byte[] body) {
        HttpUriRequestBase req = new HttpUriRequestBase("PUT",
                URI.create("http://127.0.0.1:" + serverPort + path));
        req.setHeader("api-key", "proxyKey1");
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.LEGACY);
        builder.addBinaryBody("attachment", body, ContentType.create(contentType), "image.png");
        req.setEntity(builder.build());
        try {
            return client.execute(req, response -> {
                int status = response.getCode();
                String answer = response.getEntity() == null ? null : EntityUtils.toString(response.getEntity());
                Map<String, String> headers = new HashMap<>();
                for (Header header : response.getHeaders()) {
                    headers.put(header.getName(), header.getValue());
                }
                return new Response(status, answer, headers);
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

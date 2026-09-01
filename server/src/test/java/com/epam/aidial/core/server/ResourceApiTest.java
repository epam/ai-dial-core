package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.InvitationLink;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
class ResourceApiTest extends ResourceBaseTest {

    @Test
    void testEncodedDecodedNextToken() {
        int total = 100;
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < total; i++) {
            Response response = resourceRequest(HttpMethod.PUT, "/folder/conversation%20" + i, CONVERSATION_BODY_1);
            assertEquals(response.status(), 200);
            expected.add("conversation " + i);
        }

        String path = "/v1/metadata/conversations/" + bucket + "/folder/";
        Set<String> actual = new HashSet<>();
        String token = null;
        do {
            String queryParams = token == null ? "limit=10" : "limit=10&token=" + token;
            Response response = send(HttpMethod.GET, path, queryParams, "");
            verify(response, 200);

            JsonObject body = new JsonObject(response.body());
            for (Object item : body.getJsonArray("items")) {
                actual.add(((JsonObject) item).getString("name"));
            }
            // nextToken is an opaque value - the caller must percent-encode it like any other query parameter
            String nextToken = body.getString("nextToken");
            token = nextToken == null ? null : URLEncoder.encode(nextToken, StandardCharsets.UTF_8);
        } while (token != null);

        assertEquals(expected, actual);
    }

    @Test
    void testEncodedDecodedNextToken2() {
        // "+" is a legal path character, so path encoding leaves it as is,
        // but the query parser rewrites it into a space when the token comes back
        verifySecondPage("plus", "a+1", "a+2");
        // "%" is escaped by path encoding, so decoding the token twice (as a query param and as a path) loses it
        verifySecondPage("percent", "b%2520x", "b%2520y");
    }

    private void verifySecondPage(String folder, String first, String second) {
        verify(resourceRequest(HttpMethod.PUT, "/" + folder + "/" + first, CONVERSATION_BODY_1), 200);
        verify(resourceRequest(HttpMethod.PUT, "/" + folder + "/" + second, CONVERSATION_BODY_1), 200);

        String path = "/v1/metadata/conversations/" + bucket + "/" + folder + "/";
        Response page1 = send(HttpMethod.GET, path, "limit=1", "");
        verify(page1, 200);

        String token = new JsonObject(page1.body()).getString("nextToken");
        assertNotNull(token);

        // the client sends the token back exactly as it was given, as an opaque value
        Response page2 = send(HttpMethod.GET, path, "limit=1&token=" + token, "");
        verify(page2, 200);

        JsonObject item = new JsonObject(page2.body()).getJsonArray("items").getJsonObject(0);
        assertEquals(UrlUtil.decodePath(second), item.getString("name"));
    }

    @Test
    void testWorkflow() {
        EventStream events = subscribe("""
                 {
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation"
                    }
                  ]
                 }
                """);

        Response response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verify(response, 404, "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation");

        response = metadata("/folder/");
        verify(response, 404, "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/");

        response = metadata("/");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/\"");

        response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        response = metadata("/?recursive=true");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1, "if-none-match", "*");
        verifyNotExact(response, 412, "Resource already exists");

        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verifyJson(response, 200, CONVERSATION_BODY_1);

        response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_2);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder");

        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verifyJson(response, 200, CONVERSATION_BODY_2);

        response = metadata("/folder/");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        response = metadata("/");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/\"");

        response = resourceRequest(HttpMethod.DELETE, "/folder/conversation");
        verify(response, 200, "");

        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verify(response, 404, "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation");

        response = resourceRequest(HttpMethod.DELETE, "/folder/conversation");
        verify(response, 404, "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation");

        response = metadata("/folder/");
        verify(response, 404, "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/");

        response = resourceRequest(HttpMethod.PUT, "/folder/conversation2", CONVERSATION_BODY_2);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2\"");

        verifyJsonNotExact("""
                {
                  "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                  "action" : "CREATE",
                  "timestamp" : "@ignore",
                  "etag" : "\\"7c2fb99c2a57e8f50360f659f9f8a163\\"",
                  "senderPodId" : "@ignore"
                }
                """, events.take());

        verifyJsonNotExact("""
                {
                  "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                  "action" : "UPDATE",
                  "timestamp" : "@ignore",
                  "etag" : "\\"9295391fd4aab5bd32f63749b228b3f5\\"",
                  "senderPodId" : "@ignore"
                }
                """, events.take());

        verifyJsonNotExact("""
                {
                  "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                  "action" : "DELETE",
                  "timestamp" : "@ignore",
                  "senderPodId" : "@ignore"
                }
                """, events.take());

        events.close();
    }

    @Test
    public void testIfMatch() {
        Response response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verify(response, 404, "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation");

        response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"etag\":\"\\\"7c2fb99c2a57e8f50360f659f9f8a163\\\"\"");
        assertEquals("\"7c2fb99c2a57e8f50360f659f9f8a163\"", response.headers().get("etag"));
        assertEquals("etag", response.headers().get("access-control-expose-headers"));

        response = resourceRequest(HttpMethod.GET, "/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);
        assertEquals("\"7c2fb99c2a57e8f50360f659f9f8a163\"", response.headers().get("etag"));
        assertEquals("etag", response.headers().get("access-control-expose-headers"));

        response = metadata("/folder/conversation");
        verifyNotExact(response, 200, "\"etag\":\"\\\"7c2fb99c2a57e8f50360f659f9f8a163\\\"\"");

        response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_2, "if-match", "123");
        verifyNotExact(response, 412, "If-match condition is failed for etag");

        response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_2, "if-match", "\"7c2fb99c2a57e8f50360f659f9f8a163\"");
        verifyNotExact(response, 200, "\"etag\":\"\\\"9295391fd4aab5bd32f63749b228b3f5\\\"\"");
        assertEquals("\"9295391fd4aab5bd32f63749b228b3f5\"", response.headers().get("etag"));
        assertEquals("etag", response.headers().get("access-control-expose-headers"));

        response = metadata("/folder/conversation");
        verifyNotExact(response, 200, "\"etag\":\"\\\"9295391fd4aab5bd32f63749b228b3f5\\\"\"");

        response = resourceRequest(HttpMethod.DELETE, "/folder/conversation", "", "if-match", "123");
        verifyNotExact(response, 412, "If-match condition is failed for etag");

        response = resourceRequest(HttpMethod.DELETE, "/folder/conversation", "", "if-match", "\"9295391fd4aab5bd32f63749b228b3f5\"");
        verify(response, 200, "");
    }

    @Test
    void testEtagIfMatchSpecCompliant() {

        // region resource does not exists
        Response response = resourceRequest(HttpMethod.GET, "/resource", "", "if-match", "some");
        verify(response, 412);

        response = resourceRequest(HttpMethod.GET, "/resource", "", "if-match", "*");
        verify(response, 412);

        response = resourceRequest(HttpMethod.PUT, "/resource", "", "if-match", "some");
        verify(response, 412);

        response = resourceRequest(HttpMethod.PUT, "/resource", "", "if-match", "*");
        verify(response, 412);

        response = resourceRequest(HttpMethod.DELETE, "/resource", "", "if-match", "some");
        verify(response, 412);

        response = resourceRequest(HttpMethod.DELETE, "/resource", "", "if-match", "*");
        verify(response, 412);
        // endregion

        // region resource does exist
        response = resourceRequest(HttpMethod.PUT, "/resource", "");
        verify(response, 200);
        String etag = response.headers().get("etag");
        Assertions.assertNotNull(etag);

        response = resourceRequest(HttpMethod.GET, "/resource", "", "if-match", etag);
        verify(response, 200);

        response = resourceRequest(HttpMethod.GET, "/resource", "", "if-match", "some");
        verify(response, 412);

        response = resourceRequest(HttpMethod.GET, "/resource", "", "if-match", "*");
        verify(response, 200);

        response = resourceRequest(HttpMethod.PUT, "/resource", "", "if-match", etag);
        verify(response, 200);
        etag = response.headers().get("etag");
        Assertions.assertNotNull(etag);

        response = resourceRequest(HttpMethod.PUT, "/resource", "", "if-match", "some");
        verify(response, 412);

        response = resourceRequest(HttpMethod.PUT, "/resource", "", "if-match", "*");
        verify(response, 200);
        etag = response.headers().get("etag");
        Assertions.assertNotNull(etag);

        response = resourceRequest(HttpMethod.DELETE, "/resource", "", "if-match", "some");
        verify(response, 412);

        response = resourceRequest(HttpMethod.DELETE, "/resource", "", "if-match", etag);
        verify(response, 200);

        response = resourceRequest(HttpMethod.PUT, "/resource", "", "if-match", "*");
        verify(response, 412);

        response = resourceRequest(HttpMethod.DELETE, "/resource", "", "if-match", "some");
        verify(response, 412);

        response = resourceRequest(HttpMethod.DELETE, "/resource", "", "if-match", "*");
        verify(response, 412);
        // endregion
    }

    @Test
    void testEtagIfNoneMatchSpecCompliant() {

        // region resource does not exists
        Response response = resourceRequest(HttpMethod.GET, "/resource", "", "if-none-match", "some");
        verify(response, 404);

        response = resourceRequest(HttpMethod.GET, "/resource", "", "if-none-match", "*");
        verify(response, 404);

        response = resourceRequest(HttpMethod.PUT, "/resource", "", "if-none-match", "some");
        verify(response, 200);

        response = resourceRequest(HttpMethod.PUT, "/resource", "", "if-none-match", "*");
        verify(response, 412);

        response = resourceRequest(HttpMethod.DELETE, "/resource", "", "if-none-match", "some");
        verify(response, 200);

        response = resourceRequest(HttpMethod.DELETE, "/resource", "", "if-none-match", "*");
        verify(response, 404);
        // endregion

        // region resource does exist
        response = resourceRequest(HttpMethod.PUT, "/resource", "");
        verify(response, 200);
        String etag = response.headers().get("etag");
        Assertions.assertNotNull(etag);

        response = resourceRequest(HttpMethod.GET, "/resource", "", "if-none-match", etag);
        verify(response, 304);

        response = resourceRequest(HttpMethod.GET, "/resource", "", "if-none-match", "some");
        verify(response, 200);

        response = resourceRequest(HttpMethod.GET, "/resource", "", "if-none-match", "*");
        verify(response, 304);

        response = resourceRequest(HttpMethod.PUT, "/resource", "", "if-none-match", etag);
        verify(response, 412);

        response = resourceRequest(HttpMethod.PUT, "/resource", "", "if-none-match", "some");
        verify(response, 200);
        etag = response.headers().get("etag");
        Assertions.assertNotNull(etag);

        response = resourceRequest(HttpMethod.PUT, "/resource", "", "if-none-match", "*");
        verify(response, 412);

        response = resourceRequest(HttpMethod.DELETE, "/resource", "", "if-none-match", etag);
        verify(response, 412);

        response = resourceRequest(HttpMethod.DELETE, "/resource", "", "if-none-match", "some");
        verify(response, 200);

        response = resourceRequest(HttpMethod.PUT, "/resource", "", "if-none-match", "*");
        verify(response, 200);

        response = resourceRequest(HttpMethod.DELETE, "/resource", "", "if-none-match", "some");
        verify(response, 200);

        response = resourceRequest(HttpMethod.DELETE, "/resource", "", "if-none-match", "*");
        verify(response, 404);
        // endregion
    }

    @Test
    public void testFileUploadWithInvalidResourcePath() {
        Response response = resourceRequest(HttpMethod.PUT, "/folder/conversation.", CONVERSATION_BODY_1);
        verify(response, 400);

        response = resourceRequest(HttpMethod.PUT, "/folder./conversation", CONVERSATION_BODY_1);
        verify(response, 400);

        response = resourceRequest(HttpMethod.GET, "/folder1/conversation.");
        verify(response, 404);

        response = resourceRequest(HttpMethod.GET, "/folder1./conversation");
        verify(response, 404);
    }

    @Test
    void testMaxKeySize() {
        Response response = resourceRequest(HttpMethod.PUT, "/" + "1".repeat(900), "body");
        verify(response, 400, "Resource path exceeds max allowed size: 900");
    }

    @Test
    void testMaxContentSize() {
        Response response = resourceRequest(HttpMethod.PUT, "/folder/big", "1".repeat(64 * 1024 * 1024 + 1));
        verify(response, 413, "Request body is too large");
    }

    @Test
    void testBigContentSize() {
        String template = """
                {
                  "id": "conversation_id",
                  "name": "display_name",
                  "model": {"id": "model_id"},
                  "prompt": "%s",
                  "temperature": 1,
                  "folderId": "folder1",
                  "messages": [],
                  "assistantModelId": "assistantId",
                  "lastActivityDate": 4848683153
                 }
                """;
        String big = template.formatted("0".repeat(4 * 1024 * 1024));
        String small = template.formatted("12345");

        Response response = resourceRequest(HttpMethod.PUT, "/folder/big", big);
        verify(response, 200);

        response = resourceRequest(HttpMethod.GET, "/folder/big");
        verifyJson(response, 200, big);

        response = resourceRequest(HttpMethod.PUT, "/folder/big", small);
        verify(response, 200);

        response = resourceRequest(HttpMethod.GET, "/folder/big");
        verifyJson(response, 200, small);

        response = resourceRequest(HttpMethod.DELETE, "/folder/big");
        verify(response, 200);

        response = resourceRequest(HttpMethod.GET, "/folder/big");
        verify(response, 404);
    }

    @Test
    void testIfNoneMatch() {
        Response response = resourceRequest(HttpMethod.PUT, "/folder/big", CONVERSATION_BODY_1, "if-none-match", "unsupported");
        verifyNotExact(response, 200, "big");

        response = resourceRequest(HttpMethod.GET, "/folder/big", CONVERSATION_BODY_1, "if-none-match", "unsupported");
        verifyNotExact(response, 200, CONVERSATION_BODY_1);

        response = resourceRequest(HttpMethod.GET, "/folder/big", CONVERSATION_BODY_1, "if-none-match", "\"7c2fb99c2a57e8f50360f659f9f8a163\"");
        assertEquals(304, response.status());
        assertEquals("\"7c2fb99c2a57e8f50360f659f9f8a163\"", response.headers().get("etag"));

        response = resourceRequest(HttpMethod.PUT, "/folder/big", CONVERSATION_BODY_1, "if-none-match", "\"7c2fb99c2a57e8f50360f659f9f8a163\"");
        assertEquals(412, response.status());
    }

    @Test
    void testRandom() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 100; i++) {
            int type = random.nextInt(0, 3);
            int id = random.nextInt(0, 20);
            int size = random.nextInt(0, 2);
            String body = size == 0 ? CONVERSATION_BODY_1 : CONVERSATION_BODY_2;
            String path = "/folder1/folder2/conversation" + id;
            String notFound = "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST" + path;

            if (type == 0) {
                Response resource = resourceRequest(HttpMethod.PUT, path, body);
                verifyNotExact(resource, 200, path);

                resource = resourceRequest(HttpMethod.GET, path);
                verify(resource, 200, body);
                continue;
            }

            if (type == 1) {
                Response response = resourceRequest(HttpMethod.DELETE, path);
                verify(response, response.ok() ? 200 : 404, response.ok() ? "" : notFound);
                continue;
            }

            if (type == 2) {
                Response response = resourceRequest(HttpMethod.GET, path);
                if (response.status() == 200) {
                    // flip body
                    body = size == 0 ? CONVERSATION_BODY_2 : CONVERSATION_BODY_1;
                    Response resource = resourceRequest(HttpMethod.PUT, path, body);
                    verifyNotExact(resource, 200, path);

                    resource = resourceRequest(HttpMethod.GET, path);
                    verify(resource, 200, body);
                } else {
                    verify(response, 404, notFound);
                }
                continue;
            }

            throw new IllegalStateException("Unreachable code");
        }
    }

    @Test
    void testInvalidSubscription() {
        Response response = operationRequest("/v1/ops/resource/subscribe", """
                 {
                  "resources": [
                    {
                      "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation"
                    }
                  ]
                 }
                """);

        verify(response, 400, "resource type is not supported: publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation");

        response = operationRequest("/v1/ops/resource/subscribe", """
                 {
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/"
                    }
                  ]
                 }
                """);

        verify(response, 400, "resource folder is not supported: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/");

        response = operationRequest("/v1/ops/resource/subscribe", """
                 {
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation"
                    }
                  ]
                 }
                """, "api-key", "proxyKey2");

        verify(response, 403, "Resource is not allowed: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation");
    }

    @Test
    void testHeartbeat() {
        try (EventStream events = subscribe("""
                 {
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation"
                    }
                  ]
                 }
                """)) {
            assertEquals(0, events.peekHeartbeats());
            assertTrue(events.takeHeartbeat(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void testApplicationWithTypeSchemaCreation_Ok_FilesAccessible() {
        Response response = upload(HttpMethod.PUT, "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_file1.txt", null, """
                  Test1
                """);

        Assertions.assertEquals(200, response.status());

        response = upload(HttpMethod.PUT, "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_file2.txt", null, """
                  Test2
                """);

        Assertions.assertEquals(200, response.status());

        response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_files", null, """
                  {
                      "displayName": "test_app",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                       "applicationProperties": {
                        "property1": "test property1",
                        "property2": "test property2",
                        "property3": [
                                "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_file1.txt",
                                "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_file2.txt"
                        ]
                       },
                       "userRoles": [
                            "Admin"
                       ],
                       "forwardAuthToken": true,
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """);
        Assertions.assertEquals(200, response.status());
    }

    @Test
    void testApplicationWithTypeSchemaCreation_Ok_Folder() {
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_folder", null, """
                  {
                      "displayName": "test_app",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                       "applicationProperties": {
                        "property1": "test property1",
                        "property2": "test property2",
                        "property3": [
                                "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/xyz/"
                        ]
                       },
                       "userRoles": [
                            "Admin"
                       ],
                       "forwardAuthToken": true,
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """);
        Assertions.assertEquals(200, response.status());
    }

    @Test
    void testApplicationWithTypeSchemaCreation_Failed_FailAccessFile() {
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_files_failed", null, """
                  {
                      "displayName": "test_app",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                      "applicationProperties": {
                        "property1": "test property1",
                        "property2": "test property2",
                        "property3": [
                                "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/unexisting_folder/unexisting_file.txt"
                        ]
                       },
                       "userRoles": [
                            "Admin"
                       ],
                       "forwardAuthToken": true,
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """);
        Assertions.assertEquals(403, response.status());
    }

    @Test
    void testApplicationWithTypeSchemaCreation_Failed_FailMissingProps() {
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_props_failed", null, """
                  {
                      "displayName": "test_app",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                       "applicationProperties": {},
                       "userRoles": [
                            "Admin"
                       ],
                       "forwardAuthToken": true,
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """);
        Assertions.assertEquals(400, response.status());
    }

    @Test
    void testApplicationWithTypeSchemaGet_ReturnedInvalid_WhenAppDoesNotConformToSchema() {
        //create valid app
        Response response = upload(HttpMethod.PUT, "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_file1.txt", null, """
                  Test1
                """);

        Assertions.assertEquals(200, response.status());

        response = upload(HttpMethod.PUT, "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_file2.txt", null, """
                  Test2
                """);

        Assertions.assertEquals(200, response.status());

        response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_to_fail", null, """
                  {
                      "displayName": "test_app",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                       "applicationProperties": {
                        "property1": "test property1",
                        "property2": "test property2",
                        "property3": [
                                "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_file1.txt",
                                "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_file2.txt"
                        ]
                       },
                       "userRoles": [
                            "Admin"
                       ],
                       "forwardAuthToken": true,
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """);
        Assertions.assertEquals(200, response.status());

        //share valid app
        response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_to_fail",
                      "permissions": [ "READ" ]
                    }
                  ]
                }
                """);
        verify(response, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(response.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2");
        verify(response, 200);

        //broke the app
        ResourceDescriptor descriptor =
                ResourceDescriptorFactory.fromAnyUrl("applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_to_fail", this.dial.getEncryptionService());

        this.dial.getResourceService().putResource(descriptor, """
                  {
                      "displayName": "test_app",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                       "applicationProperties": {},
                       "userRoles": [
                            "Admin"
                       ],
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """, EtagHeader.ANY);

        //making get request from other user and check invalid flag

        response = send(HttpMethod.GET, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_to_fail", null, null, "Api-key", "proxyKey2");

        verifyJsonNotExact(response, 200, """
                {
                    "user_roles" : [ "Admin" ],
                    "display_name" : "test_app",
                    "icon_url" : "https://mydial.somewhere.com/app-icon.svg",
                    "description" : "My application description",
                    "forward_auth_token" : false,
                    "defaults" : { },
                    "responses_defaults" : { },
                    "interceptors" : [ ],
                    "description_keywords" : [ ],
                    "max_retry_attempts" : 1,
                    "author" : "EPM-RTC-GPT",
                    "created_at" : "@ignore",
                    "updated_at" : "@ignore",
                    "dependencies" : [ ],
                    "application_type_schema_id" : "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                    "invalid" : true,
                    "routes" : { }
                }
                """);
    }
}
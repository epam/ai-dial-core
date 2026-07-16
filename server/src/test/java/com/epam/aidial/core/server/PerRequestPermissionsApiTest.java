package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.service.AdminManagedFieldsWriteMode;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import okhttp3.mockwebserver.MockResponse;
import org.apache.commons.lang3.mutable.MutableObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class PerRequestPermissionsApiTest extends ResourceBaseTest {

    @SuppressWarnings("checkstyle:LineLength")
    @Test
    public void testWorkflow() {
        String requestBody = """
                {
                  "model": {
                    "id": "app1"
                  },
                 "messages": [
                  {
                     "content": "How are you?",
                     "role": "user"
                  }
                 ]
                 }
                """;
        String responseBody = """
                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"this is a"}}],"usage":null}\r
                data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"very long long"}}],"usage":null}\r
                data: {"id":"chatcmpl-3","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"answer"}}],"usage":null}\r
                data: {"id":"chatcmpl-4","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":"stop","delta":{}}], "usage":{"completion_tokens": 20, "prompt_tokens": 20, "total_tokens": 40}}\r
                data: [DONE]\r
                """;
        // make app1 and app2 public once. Eliminate a long publication process
        ApplicationService applicationService = dial.getProxy().getApplicationService();
        Application app1 = new Application();
        app1.setEndpoint("http://localhost:17323/app1");
        applicationService.putApplication(ResourceDescriptorFactory.fromPublicUrl("applications/public/app1"),
                EtagHeader.ANY, null, app1, false, AdminManagedFieldsWriteMode.INHERIT_ONLY);
        Application app2 = new Application();
        app2.setEndpoint("http://localhost:17323/app2");
        applicationService.putApplication(ResourceDescriptorFactory.fromPublicUrl("applications/public/app2"),
                EtagHeader.ANY, null, app2, false, AdminManagedFieldsWriteMode.INHERIT_ONLY);

        try (TestWebServer server = new TestWebServer(17323)) {
            MutableObject<String> readFileUrl = new MutableObject<>();
            MutableObject<String> deleteFileUrl = new MutableObject<>();
            TestWebServer.Handler handler1 = request -> {
                String apiKey = request.getHeader(Proxy.HEADER_API_KEY);
                try {
                    // get app1 bucket
                    Response response = send(HttpMethod.GET, "/v1/bucket", null, "", "api-key", apiKey);
                    assertEquals(200, response.status());
                    String appBucket = new JsonObject(response.body()).getString("bucket");
                    assertNotNull(appBucket);

                    readFileUrl.setValue("files/%s/folder1/file1.txt".formatted(appBucket));
                    deleteFileUrl.setValue("files/%s/folder2/file2.txt".formatted(appBucket));

                    // upload file1
                    response = upload(HttpMethod.PUT, "/v1/" + readFileUrl.get(), null, """
                            some some
                            """, "api-key", apiKey);
                    verify(response, 200);

                    // upload file2
                    response = upload(HttpMethod.PUT, "/v1/" + deleteFileUrl.get(), null, """
                            some some
                            """, "api-key", apiKey);
                    verify(response, 200);

                    // grant permissions to app2
                    response = send(HttpMethod.POST, "/v1/ops/resource/per-request-permissions/grant", null, """
                            {
                             "resourcePermissions": [
                               {
                                 "url": "files/%s/folder1/file1.txt",
                                 "permissions": ["READ"]
                               },
                               {
                                 "url": "files/%s/folder2/",
                                 "permissions": ["WRITE"]
                               }
                             ],
                             "receiver": "applications/public/app2"
                            }
                            """.formatted(appBucket, appBucket), "api-key", apiKey);
                    verify(response, 200);

                    // send request to app1
                    response = send(HttpMethod.POST, "/openai/deployments/applications/public/app2/chat/completions",
                            null, requestBody, "api-key", apiKey,
                            "content-type", Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);
                    verify(response, 200);

                    // view permissions shared by app1 to app2
                    response = send(HttpMethod.POST, "/v1/ops/resource/per-request-permissions/list", null, """
                            {
                             "with": "OTHERS"
                            }
                            """, "api-key", apiKey);
                    verify(response, 200);
                    JsonArray receivers = new JsonObject(response.body()).getJsonArray("receivers");
                    assertEquals(1, receivers.size());
                    assertEquals("applications/public/app2", receivers.getJsonObject(0).getString("receiver"));

                    // revoke permissions granted by app1 to app2
                    response = send(HttpMethod.POST, "/v1/ops/resource/per-request-permissions/revoke", null, """
                            {
                             "resourcePermissions": [
                               {
                                 "url": "files/%s/folder1/file1.txt",
                                 "permissions": ["READ"]
                               },
                               {
                                 "url": "files/%s/folder2/",
                                 "permissions": ["WRITE"]
                               }
                             ],
                             "receiver": "applications/public/app2"
                            }
                            """.formatted(appBucket, appBucket), "api-key", apiKey);
                    verify(response, 200);

                    // make sure app1 doesn't have access to those resources
                    response = send(HttpMethod.POST, "/openai/deployments/applications/public/app2/chat/completions",
                            null, requestBody, "api-key", apiKey,
                            "content-type", Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);
                    verify(response, 403);

                    // grant permissions to app2 on resources that app1 doesn't have access
                    response = send(HttpMethod.POST, "/v1/ops/resource/per-request-permissions/grant", null, """
                            {
                             "resourcePermissions": [
                               {
                                 "url": "files/%s/myfiles/file.txt",
                                 "permissions": ["READ"]
                               }
                             ],
                             "receiver": "applications/public/app2"
                            }
                            """.formatted(bucket), "api-key", apiKey);
                    verify(response, 403);

                    MockResponse mockResponse = new MockResponse();
                    mockResponse.setResponseCode(200);
                    mockResponse.setChunkedBody(responseBody, 200);
                    return mockResponse;
                } catch (Throwable e) {
                    return (new MockResponse()).setResponseCode(500);
                }
            };
            server.map(HttpMethod.POST, "/app1", handler1);

            TestWebServer.Handler handler2 = request -> {
                String apiKey = request.getHeader(Proxy.HEADER_API_KEY);
                try {
                    // list permissions to resources granted to app2
                    var response = send(HttpMethod.POST, "/v1/ops/resource/per-request-permissions/list", null, """
                            {
                             "with": "ME"
                            }
                            """, "api-key", apiKey);
                    verify(response, 200);
                    JsonArray resources = new JsonObject(response.body()).getJsonArray("resources");

                    // no permissions -> return 403
                    if (resources.isEmpty()) {
                        return (new MockResponse()).setResponseCode(403);
                    }
                    assertEquals(2, resources.size());

                    // verify read access to the file1
                    response = send(HttpMethod.GET, "/v1/" + readFileUrl, null, null, "api-key", apiKey);
                    verify(response, 200);

                    // verify write access to the file2
                    response = send(HttpMethod.DELETE, "/v1/" + deleteFileUrl, null, null, "api-key", apiKey);
                    verify(response, 200);

                    MockResponse mockResponse = new MockResponse();
                    mockResponse.setResponseCode(200);
                    mockResponse.setChunkedBody(responseBody, 200);
                    return mockResponse;
                } catch (Throwable e) {
                    return (new MockResponse()).setResponseCode(500);
                }
            };
            // initiate chat completion request to app2
            server.map(HttpMethod.POST, "/app2", handler2);
            var response = send(HttpMethod.POST, "/openai/deployments/applications/public/app1/chat/completions",
                    null, requestBody, "content-type", Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);
            verify(response, 200);
        }
    }

    @Test
    public void testGrant_WithNoPerRequestKey() {
        var response = send(HttpMethod.POST, "/v1/ops/resource/per-request-permissions/grant", null, """
                {
                 "resources": [
                   {
                     "url": "files/%s/folder1/file1.txt",
                     "permissions": ["READ"]
                   },
                   {
                     "url": "files/%s/folder2/",
                     "permissions": ["WRITE"]
                   }
                 ],
                 "receiver": "applications/public/app2"
                }
                """.formatted(bucket, bucket));
        verify(response, 403);
    }

    @Test
    public void testGrant_BadRequest() {
        ApiKeyData apiKeyData = createAppKey("EPM-RTC-GPT", Map.of());
        dial.getProxy().getApiKeyStore().assignPerRequestApiKey(apiKeyData);
        String perRequestKey = apiKeyData.getPerRequestKey();
        var response = send(HttpMethod.POST, "/v1/ops/resource/per-request-permissions/grant", null, """
                {
                 "resources": [
                   {
                     "url": "files/%s/folder1/file1.txt",
                     "permissions": ["READ"]
                   },
                   {
                     "url": "files/%s/folder2/",
                     "permissions": ["WRITE"]
                   }
                 ]
                }
                """.formatted(bucket, bucket), "api-key", perRequestKey);
        verify(response, 400);

        response = send(HttpMethod.POST, "/v1/ops/resource/per-request-permissions/grant", null, """
                {
                 "resources": [
                 ],
                 "receiver": "applications/public/app1"
                }
                """, "api-key", perRequestKey);
        verify(response, 400);
    }
}

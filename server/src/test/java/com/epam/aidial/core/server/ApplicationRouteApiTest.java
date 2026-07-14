package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.InvitationLink;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ApplicationRouteApiTest extends ResourceBaseTest {

    @Test
    public void testSimpleAppRoute() {
        String responseBody = """
                {
                 "content": "some result",
                 "attachments": "file1"
                }
                """;
        try (TestWebServer server = new TestWebServer(4848)) {
            TestWebServer.Handler handler = request -> {
                MockResponse response = new MockResponse();
                response.setResponseCode(200);
                response.setBody(responseBody);
                return response;
            };
            server.map(HttpMethod.POST, "/v1/index/search", handler);

            String requestBody = """
                    {
                     "payload": "some content"
                    }
                    """;
            ResourceBaseTest.Response response = send(HttpMethod.POST, "/v1/deployments/app-route/route/v1/index/search", null, requestBody);

            verify(response, 200, responseBody);
        }
    }

    @Test
    public void testRouteStillWorksAfterUpstreamsHiddenInList() throws Exception {
        // Non-admin caller lists the statically-configured app-route application - upstreams must be hidden
        Response response = send(HttpMethod.GET, "/openai/applications/app-route");
        Assertions.assertEquals(200, response.status());
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        JsonNode routes = body.get("routes");
        Assertions.assertNotNull(routes, "routes must be present");
        Assertions.assertTrue(routes.get("index-search").path("upstreams").isMissingNode(),
                "upstreams must be hidden for non-admin caller");

        // The live Route config must not be corrupted by the listing above - the actual route request must still work
        String responseBody = """
                {
                 "content": "some result",
                 "attachments": "file1"
                }
                """;
        try (TestWebServer server = new TestWebServer(4848)) {
            TestWebServer.Handler handler = request -> {
                MockResponse mockResponse = new MockResponse();
                mockResponse.setResponseCode(200);
                mockResponse.setBody(responseBody);
                return mockResponse;
            };
            server.map(HttpMethod.POST, "/v1/index/search", handler);

            String requestBody = """
                    {
                     "payload": "some content"
                    }
                    """;
            Response routeResponse = send(HttpMethod.POST, "/v1/deployments/app-route/route/v1/index/search", null, requestBody);

            verify(routeResponse, 200, responseBody);
        }
    }

    @Test
    public void testAppRoute() {
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20custom%20application", null, """
                {
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description",
                "routes": {
                        "index-search": {
                          "paths": ["/v1/index(/[^/]+)*$"],
                          "rewritePath": true,
                          "methods": ["POST", "PUT", "DELETE"],
                          "upstreams": [{"endpoint": "http://localhost:4848"}],
                          "permissions": ["WRITE"],
                          "attachmentPaths": {
                            "requestBody": ["@.attachments[*].url"],
                            "responseBody": ["@.result.attachedFiles"]
                          }
                      }
                  }
                }
                """);
        verifyJsonNotExact(response, 200, """
                {
                "name":"my custom application",
                "parentPath":null,
                "bucket":"3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                "url":"applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20custom%20application",
                "nodeType":"ITEM",
                "resourceType":"APPLICATION",
                "createdAt": "@ignore",
                "updatedAt":"@ignore",
                "etag":"@ignore",
                "author" : "EPM-RTC-GPT"
                }
                """);
        String responseBody = """
                {
                 "content": "some result",
                 "attachments": "file1"
                }
                """;
        try (TestWebServer server = new TestWebServer(4848)) {
            TestWebServer.Handler handler = request -> {
                MockResponse mockResponse = new MockResponse();
                mockResponse.setResponseCode(200);
                mockResponse.setBody(responseBody);
                return mockResponse;
            };
            server.map(HttpMethod.POST, "/v1/index/search", handler);

            String requestBody = """
                    {
                     "payload": "some content"
                    }
                    """;
            String appPath = "/v1/deployments/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20custom%20application/route/v1/index/search";
            ResourceBaseTest.Response appResponse = send(HttpMethod.POST, appPath, null, requestBody);

            verify(appResponse, 200, responseBody);
        }
    }

    @Test
    public void testSchemaRichAppRoute() {
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, """
                {
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description",
                "application_properties" : {
                    "property1" : "test property1",
                    "property2" : "test property2"
                  },
                "application_type_schema_id" : "https://mydial.somewhere.com/custom_application_schemas/specific_application_type"
                }
                """);
        verifyJsonNotExact(response, 200, """
                {
                "name":"my-custom-application",
                "parentPath":null,
                "bucket":"3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                "url":"applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                "nodeType":"ITEM",
                "resourceType":"APPLICATION",
                "createdAt": "@ignore",
                "updatedAt":"@ignore",
                "etag":"@ignore",
                "author" : "EPM-RTC-GPT"
                }
                """);
        String responseBody = """
                {
                 "content": "some result",
                 "attachments": "file1"
                }
                """;
        try (TestWebServer server = new TestWebServer(4848)) {
            TestWebServer.Handler handler = request -> {
                MockResponse mockResponse = new MockResponse();
                mockResponse.setResponseCode(200);
                mockResponse.setBody(responseBody);
                return mockResponse;
            };
            server.map(HttpMethod.POST, "/v1/index/search", handler);

            String requestBody = """
                    {
                     "payload": "some content"
                    }
                    """;
            String appPath = "/v1/deployments/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application/route/v1/index/search";
            ResourceBaseTest.Response appResponse = send(HttpMethod.POST, appPath, null, requestBody);

            verify(appResponse, 200, responseBody);
        }
    }

    @Test
    public void testSchemaRichAppRoutesExposedInOpenAiApi() throws Exception {
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, """
                {
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description",
                "application_properties" : {
                    "property1" : "test property1",
                    "property2" : "test property2"
                  },
                "application_type_schema_id" : "https://mydial.somewhere.com/custom_application_schemas/specific_application_type"
                }
                """);
        Assertions.assertEquals(200, response.status());

        response = send(HttpMethod.GET,
                "/openai/applications/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application");
        Assertions.assertEquals(200, response.status());

        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        JsonNode routes = body.get("routes");
        Assertions.assertNotNull(routes, "routes must be present in /openai/applications response");
        JsonNode dataSync = routes.get("data_sync");
        Assertions.assertNotNull(dataSync, "schema route data_sync must be exposed");
        Assertions.assertEquals("/v1/index/search", dataSync.get("paths").get(0).asText());
        Assertions.assertEquals("POST", dataSync.get("methods").get(0).asText());
        Assertions.assertEquals(5, dataSync.get("order").asInt());
        Assertions.assertEquals("WRITE", dataSync.get("permissions").get(0).asText());
        Assertions.assertEquals("http://localhost:4848", dataSync.get("upstreams").get(0).get("endpoint").asText());
    }

    @Test
    public void testSchemaRichAppRoute_WhenRequestResponseIsNotJson() {
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, """
                {
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description",
                "application_properties" : {
                    "property1" : "test property1",
                    "property2" : "test property2"
                  },
                "application_type_schema_id" : "https://mydial.somewhere.com/custom_application_schemas/specific_application_type"
                }
                """);
        verifyJsonNotExact(response, 200, """
                {
                "name":"my-custom-application",
                "parentPath":null,
                "bucket":"3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                "url":"applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                "nodeType":"ITEM",
                "resourceType":"APPLICATION",
                "createdAt": "@ignore",
                "updatedAt":"@ignore",
                "etag":"@ignore",
                "author" : "EPM-RTC-GPT"
                }
                """);
        String responseBody = """
                OK
                """;
        try (TestWebServer server = new TestWebServer(4848)) {
            TestWebServer.Handler handler = request -> {
                MockResponse mockResponse = new MockResponse();
                mockResponse.setResponseCode(200);
                mockResponse.setBody(responseBody);
                return mockResponse;
            };
            server.map(HttpMethod.POST, "/v1/index/search", handler);

            String requestBody = """
                    1 + 1 = ?
                    """;
            String appPath = "/v1/deployments/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application/route/v1/index/search";
            ResourceBaseTest.Response appResponse = send(HttpMethod.POST, appPath, null, requestBody);

            verify(appResponse, 200, responseBody);
        }
    }

    @Test
    public void testAppRoute_WhenAutoShareFiles() {
        // user file
        Response response = upload(HttpMethod.PUT, "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/test_file1.txt", null, "Test");
        Assertions.assertEquals(200, response.status());
        // create a public app
        response = send(HttpMethod.PUT, "/v1/applications/public/my%20custom%20application", null, """
                {
                "endpoint": "http://localhost:4848/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description",
                "routes": {
                        "index-search": {
                          "paths": ["/v1/index(/[^/]+)*$"],
                          "rewritePath": true,
                          "methods": ["POST", "PUT", "DELETE"],
                          "upstreams": [{"endpoint": "http://localhost:4848"}],
                          "permissions": ["READ"],
                          "attachmentPaths": {
                            "requestBody": ["$.attachments[*].url"],
                            "responseBody": ["$.result.attachedFiles"]
                          }
                      }
                  }
                }
                """, "authorization", "admin");
        verifyJsonNotExact(response, 200, """
                {
                "name":"my custom application",
                "parentPath":null,
                "bucket":"public",
                "url":"applications/public/my%20custom%20application",
                "nodeType":"ITEM",
                "resourceType":"APPLICATION",
                "createdAt": "@ignore",
                "updatedAt":"@ignore",
                "etag":"@ignore",
                "author" : "admin user"
                }
                """);
        String responseBody = """
                {
                 "content": "some result",
                 "result": {
                  "attachedFiles": ["%s"]
                  }
                }
                """;
        try (TestWebServer server = new TestWebServer(4848)) {
            // app route handler
            TestWebServer.Handler handler = request -> {
                try {
                    // check access to the attached file from the request
                    var res = send(HttpMethod.GET, "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/test_file1.txt",
                            null, null, "api-key", request.getHeader("api-key"));
                    if (res.ok()) {
                        res = send(HttpMethod.GET, "/v1/bucket", null, "", "api-key", request.getHeader("api-key"));
                        verify(res, 200);
                        String bucket = new JsonObject(res.body()).getString("bucket");
                        res = upload(HttpMethod.PUT, "/v1/files/%s/folder/output.txt".formatted(bucket),
                                null, "result", "api-key", request.getHeader("api-key"));
                        verify(res, 200);
                        MockResponse mockResponse = new MockResponse();
                        mockResponse.setResponseCode(200);
                        mockResponse.setBody(responseBody.formatted("files/%s/folder/output.txt".formatted(bucket)));
                        return mockResponse;
                    } else {
                        return new MockResponse().setResponseCode(res.status());
                    }
                } catch (Throwable e) {
                    return new MockResponse().setResponseCode(500);
                }
            };
            // app chat completion handler
            server.map(HttpMethod.POST, "/v1/completions", request -> {
                try {
                    String requestBody = """
                            {
                             "payload": "some content",
                             "attachments": [{"url": "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/test_file1.txt"}]
                            }
                            """;
                    String appPath = "/v1/deployments/applications/public/my%20custom%20application/route/v1/index/search";
                    ResourceBaseTest.Response appResponse = send(HttpMethod.POST, appPath, null, requestBody, "api-key", request.getHeader("api-key"));

                    verify(appResponse, 200);
                    // check access to the attached file from the response
                    String url = new JsonObject(appResponse.body()).getJsonObject("result").getJsonArray("attachedFiles").getString(0);
                    var res = send(HttpMethod.GET, "/v1/" + url,
                            null, null, "api-key", request.getHeader("api-key"));
                    verify(res, 200);
                    return new MockResponse().setResponseCode(200).setBody(responseBody);
                } catch (Throwable e) {
                    return new MockResponse().setResponseCode(500);
                }
            });
            server.map(HttpMethod.POST, "/v1/index/search", handler);

            response = send(HttpMethod.POST, "/openai/deployments/applications/public/my%20custom%20application/chat/completions", null, """
                    {
                         "messages": [
                            {"role": "user", "content": "Repeat me!",
                          "custom_content": {
                            "attachments": [{"url": "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/test_file1.txt"}]
                          }
                          }
                          ]
                    }
                    """, "content-type", "application/json");
            verify(response, 200);
        }

    }

    @Test
    public void testRouteUpstreamsHiddenForReadOnlyUser() throws Exception {
        // Owner creates an application with routes containing upstreams
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/secure-app", null, """
                {
                "endpoint": "http://application1/v1/completions",
                "display_name": "Secure App",
                "routes": {
                        "data_sync": {
                          "paths": ["/v1/data/sync$"],
                          "methods": ["POST"],
                          "upstreams": [{"endpoint": "http://localhost:4848"}]
                      }
                  }
                }
                """);
        Assertions.assertEquals(200, response.status());

        // Owner GETs the app via OpenAI API - should see upstreams
        response = send(HttpMethod.GET, "/openai/applications/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/secure-app");
        Assertions.assertEquals(200, response.status());
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        JsonNode routes = body.get("routes");
        Assertions.assertNotNull(routes, "routes must be present for owner");
        JsonNode upstreams = routes.get("data_sync").get("upstreams");
        Assertions.assertNotNull(upstreams, "upstreams must be present for owner");
        Assertions.assertFalse(upstreams.isEmpty(), "upstreams must not be empty for owner");

        // Owner shares the app (read-only) with user via invitation
        response = send(HttpMethod.POST, "/v1/ops/resource/share/create", null, """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/secure-app"
                    }
                  ]
                }
                """);
        Assertions.assertEquals(200, response.status());
        InvitationLink invitationLink = ProxyUtil.convertToObject(response.body(), InvitationLink.class);
        Assertions.assertNotNull(invitationLink);

        // User accepts invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "authorization", "user");
        Assertions.assertEquals(200, response.status());

        // Read-only user GETs the app via OpenAI API - routes must be present but upstreams null
        response = send(HttpMethod.GET,
                "/openai/applications/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/secure-app",
                null, null, "authorization", "user");
        Assertions.assertEquals(200, response.status());
        body = ProxyUtil.MAPPER.readTree(response.body());
        routes = body.get("routes");
        Assertions.assertNotNull(routes, "routes must still be present for read-only user");
        Assertions.assertTrue(routes.get("data_sync").path("upstreams").isMissingNode(),
                "upstreams must be null for read-only user");
    }

}

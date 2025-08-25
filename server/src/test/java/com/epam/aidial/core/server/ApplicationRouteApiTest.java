package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
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
    public void testAppRoute() {
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, """
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

}

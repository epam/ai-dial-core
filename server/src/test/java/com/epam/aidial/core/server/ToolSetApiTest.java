package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ToolSetApiTest extends ResourceBaseTest {

    @Test
    void testToolsetCreation() {
        Response response = send(HttpMethod.PUT, "/v1/toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-toolset", null, """
                {
                "endpoint": "http://toolset/v1/mcp",
                "display_name": "My Toolset",
                "display_version": "1.0",
                "icon_url": "http://toolset/icon.svg",
                "description": "My toolset Description",
                "transport": "HTTP",
                "allowedTools": ["tool1", "tool2"]
                }
                """);
        verifyJsonNotExact(response, 200, """
                {
                "name":"my-toolset",
                "parentPath":null,
                "bucket":"3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                "url":"toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-toolset",
                "nodeType":"ITEM",
                "resourceType":"TOOL_SET",
                "createdAt": "@ignore",
                "updatedAt":"@ignore",
                "etag":"@ignore",
                "author" : "EPM-RTC-GPT"
                }
                """);

        response = send(HttpMethod.GET, "/v1/toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-toolset", null, "");
        verifyJsonNotExact(response, 200, """
                {
                   "name" : "toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-toolset",
                   "endpoint" : "http://toolset/v1/mcp",
                   "display_name" : "My Toolset",
                   "display_version" : "1.0",
                   "icon_url" : "http://toolset/icon.svg",
                   "description" : "My toolset Description",
                   "reference": "@ignore",
                   "forward_auth_token" : false,
                   "defaults" : { },
                   "interceptors" : [ ],
                   "description_keywords" : [ ],
                   "max_retry_attempts" : 1,
                   "dependencies" : [ ],
                   "transport" : "HTTP",
                   "allowed_tools" : [ "tool1", "tool2" ]
                 }
                """);
    }

    @Test
    void testToolSetListing() {

        Response response = send(HttpMethod.GET, "/openai/toolsets");
        verifyJsonNotExact(response, 200, """
                {
                   "data" : [ {
                     "id" : "git",
                     "toolset" : "git",
                     "description" : "Git remote tool set",
                     "owner" : "organization-owner",
                     "object" : "toolset",
                     "status" : "succeeded",
                     "created_at" : 1672534800,
                     "updated_at" : 1672534800,
                     "features" : {
                           "rate" : false,
                           "tokenize" : false,
                           "truncate_prompt" : false,
                           "configuration" : false,
                           "system_prompt" : true,
                           "tools" : false,
                           "seed" : false,
                           "url_attachments" : false,
                           "folder_attachments" : false,
                           "allow_resume" : true,
                           "accessible_by_per_request_key" : true,
                           "content_parts" : false,
                           "temperature" : true,
                           "addons" : true,
                           "cache" : false,
                           "auto_caching" : false,
                           "parallel_tool_calls" : true
                      },
                     "description_keywords" : [ ],
                     "max_retry_attempts" : 1,
                     "transport" : "HTTP",
                     "allowed_tools" : [ "branch", "remote" ]
                   } ],
                   "object" : "list"
                 }
                """);

        response = send(HttpMethod.PUT, "/v1/toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-toolset", null, """
                {
                "endpoint": "http://toolset/v1/mcp",
                "display_name": "My Toolset",
                "display_version": "1.0",
                "icon_url": "http://toolset/icon.svg",
                "description": "My toolset Description",
                "transport": "HTTP",
                "allowedTools": ["tool1", "tool2"]
                }
                """);
        verify(response, 200);

        response = send(HttpMethod.GET, "/openai/toolsets/toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-toolset");
        verifyJsonNotExact(response, 200, """
                {
                  "id" : "toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-toolset",
                  "toolset" : "toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-toolset",
                  "display_name": "My Toolset",
                  "display_version": "1.0",
                  "icon_url": "http://toolset/icon.svg",
                  "description": "My toolset Description",
                  "reference": "@ignore",
                  "owner": "EPM-RTC-GPT",
                  "object": "toolset",
                  "status": "succeeded",
                  "created_at" : "@ignore",
                  "updated_at" : "@ignore",
                  "features" : {
                        "rate" : false,
                        "tokenize" : false,
                        "truncate_prompt" : false,
                        "configuration" : false,
                        "system_prompt" : true,
                        "tools" : false,
                        "seed" : false,
                        "url_attachments" : false,
                        "folder_attachments" : false,
                        "allow_resume" : true,
                        "accessible_by_per_request_key" : true,
                        "content_parts" : false,
                        "temperature" : true,
                        "addons" : true,
                        "cache" : false,
                        "auto_caching" : false,
                        "parallel_tool_calls" : true
                  },
                  "description_keywords": [],
                  "max_retry_attempts": 1,
                  "transport": "HTTP",
                  "allowed_tools": [
                    "tool1",
                    "tool2"
                  ]
                }
                """);

        response = send(HttpMethod.GET, "/openai/toolsets");
        verifyJsonNotExact(response, 200, """
                {
                    "data" : [ {
                      "id" : "git",
                      "toolset" : "git",
                      "description" : "Git remote tool set",
                      "owner" : "organization-owner",
                      "object" : "toolset",
                      "status" : "succeeded",
                      "created_at" : 1672534800,
                      "updated_at" : 1672534800,
                      "features" : {
                            "rate" : false,
                            "tokenize" : false,
                            "truncate_prompt" : false,
                            "configuration" : false,
                            "system_prompt" : true,
                            "tools" : false,
                            "seed" : false,
                            "url_attachments" : false,
                            "folder_attachments" : false,
                            "allow_resume" : true,
                            "accessible_by_per_request_key" : true,
                            "content_parts" : false,
                            "temperature" : true,
                            "addons" : true,
                            "cache" : false,
                            "auto_caching" : false,
                            "parallel_tool_calls" : true
                          },
                      "description_keywords" : [ ],
                      "max_retry_attempts" : 1,
                      "transport" : "HTTP",
                      "allowed_tools" : [ "branch", "remote" ]
                    }, {
                      "id" : "toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-toolset",
                      "toolset" : "toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-toolset",
                      "display_name" : "My Toolset",
                      "display_version" : "1.0",
                      "icon_url" : "http://toolset/icon.svg",
                      "description" : "My toolset Description",
                      "reference": "@ignore",
                      "owner" : "EPM-RTC-GPT",
                      "object" : "toolset",
                      "status" : "succeeded",
                      "created_at" : "@ignore",
                      "updated_at" : "@ignore",
                      "features" : {
                            "rate" : false,
                            "tokenize" : false,
                            "truncate_prompt" : false,
                            "configuration" : false,
                            "system_prompt" : true,
                            "tools" : false,
                            "seed" : false,
                            "url_attachments" : false,
                            "folder_attachments" : false,
                            "allow_resume" : true,
                            "accessible_by_per_request_key" : true,
                            "content_parts" : false,
                            "temperature" : true,
                            "addons" : true,
                            "cache" : false,
                            "auto_caching" : false,
                            "parallel_tool_calls" : true
                          },
                      "description_keywords" : [ ],
                      "max_retry_attempts" : 1,
                      "transport" : "HTTP",
                      "allowed_tools" : [ "tool1", "tool2" ]
                    } ],
                    "object" : "list"
                  }
                """);

    }

    @Test
    public void testProxyMcpCall() {
        String mcpRequest = """
                {
                   "payload": "foo"
                }
                """;
        String mcpResponse = """
                    {
                      "result": "success"
                    }
                    """;
        TestWebServer.Handler handler = request -> {
            assertEquals(mcpRequest, request.getBody().readString(StandardCharsets.UTF_8));
            return new MockResponse().setBody(mcpResponse);
        };
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response resp = send(HttpMethod.POST, "/v1/toolset/git/mcp", null, mcpRequest);

            assertEquals(200, resp.status());
            assertEquals(mcpResponse, resp.body());
        }
    }
}

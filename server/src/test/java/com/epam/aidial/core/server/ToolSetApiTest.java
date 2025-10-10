package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
                "allowedTools": ["tool1", "tool2"],
                "authSettings": {
                   "authenticationType": "NONE"
                }
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
                   "author" : "EPM-RTC-GPT",
                   "created_at" : "@ignore",
                   "updated_at" : "@ignore",
                   "dependencies" : [ ],
                   "transport" : "HTTP",
                   "allowed_tools" : [ "tool1", "tool2" ],
                   "auth_settings" : {
                    "authentication_type" : "NONE",
                    "global_auth_status" : "SIGNED_OUT",
                    "user_level_auth_status" : "SIGNED_OUT"
                  }
                }
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
                     "display_name" : "git",
                     "description" : "Git remote tool set",
                     "reference" : "git",
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
                           "parallel_tool_calls" : true,
                           "assistant_attachments_in_request": false
                      },
                     "description_keywords" : [ ],
                     "max_retry_attempts" : 1,
                     "transport" : "HTTP",
                     "allowed_tools" : [ "branch", "remote" ],
                     "auth_settings" : {
                        "authentication_type" : "NONE"
                     }
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
                "allowedTools": ["tool1", "tool2"],
                "auth_settings" : {
                        "authentication_type" : "NONE"
                     }
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
                        "parallel_tool_calls" : true,
                        "assistant_attachments_in_request": false
                  },
                  "description_keywords": [],
                  "max_retry_attempts": 1,
                  "transport": "HTTP",
                  "allowed_tools": [
                    "tool1",
                    "tool2"
                  ],
                  "auth_settings" : {
                        "authentication_type" : "NONE",
                        "global_auth_status" : "SIGNED_OUT",
                         "user_level_auth_status" : "SIGNED_OUT"
                     }
                }
                """);

        response = send(HttpMethod.GET, "/openai/toolsets");
        verifyJsonNotExact(response, 200, """
                {
                    "data" : [ {
                      "id" : "git",
                      "toolset" : "git",
                      "display_name" : "git",
                      "description" : "Git remote tool set",
                      "reference" : "git",
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
                            "parallel_tool_calls" : true,
                            "assistant_attachments_in_request": false
                          },
                      "description_keywords" : [ ],
                      "max_retry_attempts" : 1,
                      "transport" : "HTTP",
                      "allowed_tools" : [ "branch", "remote" ],
                      "auth_settings" : {
                        "authentication_type" : "NONE"
                     }
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
                            "parallel_tool_calls" : true,
                            "assistant_attachments_in_request": false
                          },
                      "description_keywords" : [ ],
                      "max_retry_attempts" : 1,
                      "transport" : "HTTP",
                      "allowed_tools" : [ "tool1", "tool2" ],
                      "auth_settings" : {
                        "authentication_type" : "NONE",
                        "global_auth_status" : "SIGNED_OUT",
                        "user_level_auth_status" : "SIGNED_OUT"
                     }
                    } ],
                    "object" : "list"
                  }
                """);

    }

    @Test
    void testProxyMcpPostCall() {
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
            return new MockResponse().setBody(mcpResponse).setHeader("Content-Type", "text/event-stream");
        };
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response resp = send(HttpMethod.POST, "/v1/toolset/git/mcp", null, mcpRequest);

            assertEquals(200, resp.status());
            assertEquals(mcpResponse, resp.body());
        }
    }

    @Test
    void testProxyMcpPostCall_ListTools() throws JsonProcessingException {
        String mcpRequest = """
                {
                   "jsonrpc": "2.0",
                   "id": 1,
                   "method": "tools/list",
                   "params": {
                     "cursor": "optional-cursor-value"
                   }
                 }
                """;
        String mcpResponse = """
                {
                   "jsonrpc": "2.0",
                   "id": 1,
                   "result": {
                     "tools": [
                       {
                         "name": "branch",
                         "title": "Manage branches"
                       },
                       {
                         "name": "tag",
                         "title": "Manage tags"
                       }
                     ],
                     "nextCursor": "next-page-cursor"
                   }
                 }
                """;
        TestWebServer.Handler handler = request -> {
            assertEquals(mcpRequest, request.getBody().readString(StandardCharsets.UTF_8));
            return new MockResponse().setBody(mcpResponse).setHeader("Content-Type", "application/json");
        };
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response resp = send(HttpMethod.POST, "/v1/toolset/git/mcp", null,
                    mcpRequest, "Content-Type", "application/json");

            assertEquals(200, resp.status());
            var json = ProxyUtil.MAPPER.readTree(resp.body());
            ArrayNode tools = (ArrayNode) json.get("result").get("tools");
            assertEquals(1, tools.size());
            assertEquals("branch", tools.get(0).get("name").asText());
        }
    }

    @Test
    void testProxyMcpGetCall() {
        String mcpResponse = """
                    {
                      "result": "success"
                    }
                    """;
        TestWebServer.Handler handler = request -> new MockResponse().setBody(mcpResponse);
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response resp = send(HttpMethod.GET, "/v1/toolset/git/mcp");

            assertEquals(200, resp.status());
            assertEquals(mcpResponse, resp.body());
        }
    }

    @Test
    void testPublication() {
        testToolsetCreation();

        var response = operationRequest("/v1/ops/publication/create", """
                {
                  "targetFolder": "public/",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-toolset",
                      "targetUrl": "toolsets/public/my-toolset"
                    }
                  ]
                }
                """);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/approve", """
                {
                "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123"
                }
                """, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "targetFolder" : "public/",
                  "status" : "APPROVED",
                  "createdAt" : 0,
                  "resources" : [ {
                    "action" : "ADD",
                    "sourceUrl" : "toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-toolset",
                    "targetUrl" : "toolsets/public/my-toolset",
                    "reviewUrl" : "toolsets/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/my-toolset",
                    "publishCredentials": false
                  } ],
                  "resourceTypes" : [ "TOOL_SET" ],
                  "author" : "EPM-RTC-GPT"
                }
                """);

        response = send(HttpMethod.GET, "/v1/toolsets/public/my-toolset",
                null, null, "authorization", "admin");
        verifyJsonNotExact(response, 200, """
                {
                    "name" : "toolsets/public/my-toolset",
                    "endpoint" : "http://toolset/v1/mcp",
                    "display_name" : "My Toolset",
                    "display_version" : "1.0",
                    "icon_url" : "http://toolset/icon.svg",
                    "description" : "My toolset Description",
                    "reference" : "@ignore",
                    "forward_auth_token" : false,
                    "defaults" : { },
                    "interceptors" : [ ],
                    "description_keywords" : [ ],
                    "max_retry_attempts" : 1,
                    "author" : "EPM-RTC-GPT",
                    "created_at" : "@ignore",
                    "updated_at" : "@ignore",
                    "dependencies" : [ ],
                    "auth_settings" : {
                        "authentication_type" : "NONE",
                        "global_auth_status" : "SIGNED_OUT",
                        "user_level_auth_status" : "SIGNED_OUT"
                    },
                    "transport" : "HTTP",
                    "allowed_tools" : [ "tool1", "tool2" ]
                }
                """);
    }

}

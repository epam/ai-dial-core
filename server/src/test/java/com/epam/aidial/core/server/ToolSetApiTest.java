package com.epam.aidial.core.server;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.data.InvitationLink;
import com.epam.aidial.core.server.util.JsonPath;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ToolSetApiTest extends ResourceBaseTest {

    private static final String MCP_TOOL_CALL_REQUEST = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "tools/call",
                    "params": {
                        "name": "get_weather",
                        "arguments": {
                            "location": "San Francisco"
                        }
                    }
                }
                """;

    private static final String MCP_TOOL_CALL_RESPONSE = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "result": {
                        "content": [
                            {
                               "type": "text",
                                "text": "The weather in San Francisco is 72°F and sunny"
                            }
                        ]
                    }
                }
                """;

    private static final String TOOLSETS_LIST_ADMIN_RESPONSE = """
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
                        "authentication_type" : "NONE",
                        "global_auth_status": "SIGNED_OUT",
                        "user_level_auth_status": "SIGNED_OUT"
                     }
                   },
                    {
                        "id" : "my-toolset_2",
                        "toolset" : "my-toolset_2",
                        "display_name" : "my toolset 2",
                        "reference" : "my-toolset_2",
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
                          "cache" : false,
                          "auto_caching" : false,
                          "parallel_tool_calls" : true,
                          "assistant_attachments_in_request" : false
                        },
                        "description_keywords" : [ ],
                        "max_retry_attempts" : 1,
                        "auth_settings" : {
                          "authentication_type" : "API_KEY",
                          "api_key_header" : "Authorization",
                          "global_auth_status" : "SIGNED_OUT",
                          "user_level_auth_status" : "SIGNED_OUT"
                        },
                        "transport" : "HTTP",
                        "allowed_tools" : [ ]
                      } ],
                   "object" : "list"
                 }
            """;

    private static final String TOOLSETS_LIST_USER_RESPONSE = """
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
                        "authentication_type" : "NONE",
                        "global_auth_status": "SIGNED_OUT",
                        "user_level_auth_status": "SIGNED_OUT"
                     }
                   } ],
                   "object" : "list"
                 }
            """;

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
                   "forward_per_request_key" : false,
                   "auth_settings" : {
                    "authentication_type" : "NONE",
                    "global_auth_status" : "SIGNED_OUT",
                    "user_level_auth_status" : "SIGNED_OUT"
                  },
                  "transport" : "HTTP",
                  "allowed_tools" : [ "tool1", "tool2" ]
                }
                 }
                """);
    }

    @Test
    void testToolSetListing() {
        Response response = send(HttpMethod.GET, "/openai/toolsets");
        verifyJsonNotExact(response, 200, TOOLSETS_LIST_USER_RESPONSE);

        response = send(HttpMethod.PUT, "/v1/toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20toolset", null, """
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

        response = send(HttpMethod.GET, "/openai/toolsets/toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20toolset");
        verifyJsonNotExact(response, 200, """
                {
                  "id" : "toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20toolset",
                  "toolset" : "toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20toolset",
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
                        "authentication_type" : "NONE",
                        "global_auth_status" : "SIGNED_OUT",
                        "user_level_auth_status" : "SIGNED_OUT"
                     }
                    }, {
                        "id" : "toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20toolset",
                        "toolset" : "toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20toolset",
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
    void testProxyMcpPostCall_WhenToolsetIsAppType() {
        var response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20app", null, """
                {
                "display_name": "My App",
                "display_version": "1.0",
                "icon_url": "http://apprunner/icon.svg",
                "description": "My app Description",
                "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_toolset_type",
                "applicationProperties": {
                  "property1": "foo",
                  "property2": "bar"
                  }
                }
                """);
        verify(response, 200);

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
            try {
                assertNotNull(request.getHeader(Proxy.HEADER_API_KEY));
                String body = request.getBody().readString(StandardCharsets.UTF_8);
                JsonNode tree = ProxyUtil.MAPPER.readTree(body);
                String basePath = "params._meta.ai_dial_config";
                JsonNode node = JsonPath.read(tree, basePath + ".property1");
                assertNotNull(node);
                assertEquals("foo", node.asText());
                node = JsonPath.read(tree, basePath + ".property2");
                assertNotNull(node);
                assertEquals("bar", node.asText());
                return new MockResponse().setBody(mcpResponse).setHeader("Content-Type", "application/json");
            } catch (Throwable e) {
                return new MockResponse().setResponseCode(500);
            }
        };
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response resp = send(HttpMethod.POST, "/v1/toolset/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20app/mcp",
                    null, mcpRequest, "Content-type", "application/json");

            assertEquals(200, resp.status());
            assertEquals(mcpResponse, resp.body());
        }
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
            assertNotNull(request.getHeader(Proxy.HEADER_API_KEY));
            return new MockResponse().setBody(mcpResponse).setHeader("Content-Type", "text/event-stream");
        };
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response resp = send(HttpMethod.POST, "/v1/toolset/git/mcp", null, mcpRequest);

            assertEquals(200, resp.status());
            assertEquals(mcpResponse, resp.body());
        }
    }

    @Test
    void testProxyMcpPostCall_WhenToolsetNotFound() {
        String mcpRequest = """
                {
                   "payload": "foo"
                }
                """;

        Response resp = send(HttpMethod.POST, "/v1/toolset/unknown/mcp", null, mcpRequest);

        assertEquals(404, resp.status());
    }

    @Test
    void testProxyMcp_TerminateSession() {
        String mcpRequest = """                
                """;
        String mcpResponse = """                
                """;
        TestWebServer.Handler handler = request -> {
            assertEquals(mcpRequest, request.getBody().readString(StandardCharsets.UTF_8));
            assertNotNull(request.getHeader("Mcp-Session-Id"));
            assertNotNull(request.getHeader(Proxy.HEADER_API_KEY));
            return new MockResponse().setBody(mcpResponse).setHeader("Content-Type", "text/event-stream");
        };
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response resp = send(HttpMethod.DELETE, "/v1/toolset/git/mcp", null, mcpRequest, "Mcp-Session-Id", "123");

            assertEquals(200, resp.status());
            assertEquals(mcpResponse, resp.body());
        }
    }

    @Test
    void testProxyMcpPostCall_EmptyPayload() {
        TestWebServer.Handler handler = request -> {
            assertEquals("", request.getBody().readString(StandardCharsets.UTF_8));
            return new MockResponse().setResponseCode(400).setBody("empty payload");
        };
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response resp = send(HttpMethod.POST, "/v1/toolset/git/mcp", null,
                    "", "Content-Type", "application/json");

            assertEquals(400, resp.status());
            assertEquals("empty payload", resp.body());
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
    void testProxyMcpPostCall_ListAllTools() throws JsonProcessingException {
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
            // operation is not allowed for a regular user
            Response resp = send(HttpMethod.POST, "/v1/toolset/git/mcp?useAllowedTools=false", null,
                    mcpRequest, "Content-Type", "application/json");
            assertEquals(403, resp.status());
            // admin is allowed to view all tools
            resp = send(HttpMethod.POST, "/v1/toolset/git/mcp?useAllowedTools=false", null,
                    mcpRequest, "Content-Type", "application/json", "authorization", "admin");
            assertEquals(200, resp.status());
            var json = ProxyUtil.MAPPER.readTree(resp.body());
            ArrayNode tools = (ArrayNode) json.get("result").get("tools");
            assertEquals(2, tools.size());
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
                    "forward_per_request_key" : false,
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

    @Test
    void testProxyMcpCallWithSharedToolSet() {
        // create ToolSet with admin JWT
        Response response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset%201@", null,  TOOLSET_CREATE_REQEUST_BODY,
                "authorization", "admin");
        verifyNotExact(response, 200, "\"url\":\"toolsets/4X25dj1mja51jykqxsXnCH/toolset%201@\"");

        // signin into toolset with admin JWT
        response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                {
                    "url": "toolsets/4X25dj1mja51jykqxsXnCH/toolset 1@",
                    "credentialsLevel": "GLOBAL",
                    "authenticationType": "API_KEY",
                    "api_key": "Bearer api_key"
                }
                """, "authorization", "admin");
        verify(response, 200, "true");

        // initialize share request
        response = send(HttpMethod.POST, "/v1/ops/resource/share/create", null, """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "toolsets/4X25dj1mja51jykqxsXnCH/toolset%201@",
                      "shareCredentials": "true"
                    }
                  ]
                }
                """, "authorization", "admin");
        verify(response, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(response.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        response = send(HttpMethod.GET, "/v1/invitations", null, null, "authorization", "admin");
        verifyNotExact(response, 200, "\"url\":\"toolsets/4X25dj1mja51jykqxsXnCH/toolset%201@\"");
        verifyNotExact(response, 200, "\"url\":\"credentials/4X25dj1mja51jykqxsXnCH/toolsets/4X25dj1mja51jykqxsXnCH/toolset%201@\"");
        verifyNotExact(response, 200, "\"permissions\":[\"READ\"]");

        // verify user do not have access to the toolset
        response = send(HttpMethod.GET, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset%20@", null, null, "authorization", "user");
        verify(response, 403);

        // user accepts invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "authorization", "user");
        verify(response, 200);

        // verify user has access to the toolset
        response = send(HttpMethod.GET, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset%201@", null, null, "authorization", "user");
        verify(response, 200);

        String mcpRequest = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "tools/call",
                    "params": {
                        "name": "get_weather",
                        "arguments": {
                            "location": "San Francisco"
                        }
                    }
                }
                """;

        String mcpResponse = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "result": {
                    "content": [
                      {
                        "type": "text",
                        "text": "The weather in San Francisco is 72°F and sunny"
                      }
                    ]
                  }
                }
                """;

        // use mcp with user's per request api key
        TestWebServer.Handler handler = request -> new MockResponse().setBody(mcpResponse).setHeader("Content-Type", "application/json");
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            ApiKeyData userAppKey = createAppKey("user", Map.of(
                    "toolsets/4X25dj1mja51jykqxsXnCH/toolset%20@",
                    new AutoSharedData(Set.of(ResourceAccessType.READ))));
            apiKeyStore.assignPerRequestApiKey(userAppKey);

            Response resp = send(HttpMethod.POST, "/v1/toolset/toolsets/4X25dj1mja51jykqxsXnCH/toolset%201@/mcp", null,
                    mcpRequest, "Content-Type", "application/json", "api-key", userAppKey.getPerRequestKey());

            assertEquals(200, resp.status());
            assertEquals(mcpResponse, resp.body());
        }
    }

    @Test
    void testProxyMcpCallWithUserConsentRequiredButNotProvided() throws JsonProcessingException {
        // create ToolSet with required user's consent
        Response response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset@", null, """
            {
                "endpoint": "http://localhost:9876",
                "transport": "HTTP",
                "allowedTools": [],
                "auth_settings": {
                    "authentication_type": "API_KEY",
                    "api_key_header": "Authorization"
                },
                "features": {
                    "consentRequired": "true"
                }
            }
                """, "authorization", "admin");
        verifyNotExact(response, 200, "\"url\":\"toolsets/4X25dj1mja51jykqxsXnCH/toolset@\"");

        // signin into toolset
        response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                {
                    "url": "toolsets/4X25dj1mja51jykqxsXnCH/toolset@",
                    "credentialsLevel": "GLOBAL",
                    "authenticationType": "API_KEY",
                    "api_key": "Bearer api_key"
                }
                """, "authorization", "admin");
        verify(response, 200, "true");

        // verify user consent is not provided
        response = send(HttpMethod.GET, "/v1/consent/toolsets/4X25dj1mja51jykqxsXnCH/toolset@", null, null, "authorization", "admin");
        verify(response, 200);
        ObjectNode node = (ObjectNode) ProxyUtil.MAPPER.readTree(response.body());
        assertFalse(node.get("accepted").asBoolean());

        // use mcp
        TestWebServer.Handler handler = request -> new MockResponse()
                .setBody(MCP_TOOL_CALL_RESPONSE)
                .setHeader("Content-Type", "application/json");
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            ApiKeyData adminAppKey = createAppKey("user", Map.of(
                    "toolsets/4X25dj1mja51jykqxsXnCH/toolset@",
                    new AutoSharedData(Set.of(ResourceAccessType.READ))));
            apiKeyStore.assignPerRequestApiKey(adminAppKey);

            Response resp = send(HttpMethod.POST, "/v1/toolset/toolsets/4X25dj1mja51jykqxsXnCH/toolset@/mcp", null,
                    MCP_TOOL_CALL_REQUEST, "Content-Type", "application/json", "authorization", "admin");

            assertEquals(403, resp.status());
        }
    }

    @Test
    void testProxyMcpCallWithUserConsentRequiredAndProvided() throws JsonProcessingException {
        // create ToolSet with required user's consent
        Response response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset@", null,
                """
                {
                    "endpoint": "http://localhost:9876",
                    "transport": "HTTP",
                    "allowedTools": [],
                    "auth_settings": {
                        "authentication_type": "API_KEY",
                        "api_key_header": "Authorization"
                    },
                    "features": {
                        "consentRequired": "true"
                    }
                }
                """,
                "authorization", "admin");
        verifyNotExact(response, 200, "\"url\":\"toolsets/4X25dj1mja51jykqxsXnCH/toolset@\"");

        response = send(HttpMethod.PUT, "/v1/applications/4X25dj1mja51jykqxsXnCH/my-app", null, """
                {
                  "endpoint": "http://application1/v1/completions",
                  "display_name": "My Custom Application",
                  "display_version": "1.0",
                  "icon_url": "http://application1/icon.svg",
                  "description": "My Custom Application Description",
                  "dependencies": ["toolsets/4X25dj1mja51jykqxsXnCH/toolset@"]
                }
                """,
                "authorization", "admin");
        verify(response, 200);

        response = send(HttpMethod.GET, "/v1/consent/applications/4X25dj1mja51jykqxsXnCH/my-app", null, null, "authorization", "admin");
        verify(response, 200);
        ObjectNode node = (ObjectNode) ProxyUtil.MAPPER.readTree(response.body());
        assertFalse(node.get("accepted").asBoolean());

        node.remove("accepted");
        response = send(HttpMethod.POST, "/v1/consent/applications/4X25dj1mja51jykqxsXnCH/my-app", null, node.toString(), "authorization", "admin");
        verify(response, 200);

        response = send(HttpMethod.GET, "/v1/consent/applications/4X25dj1mja51jykqxsXnCH/my-app", null, null, "authorization", "admin");
        verify(response, 200);
        node = (ObjectNode) ProxyUtil.MAPPER.readTree(response.body());
        assertTrue(node.get("accepted").asBoolean());

        // signin into toolset
        response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                {
                    "url": "toolsets/4X25dj1mja51jykqxsXnCH/toolset@",
                    "credentialsLevel": "GLOBAL",
                    "authenticationType": "API_KEY",
                    "api_key": "Bearer api_key"
                }
                """, "authorization", "admin");
        verify(response, 200, "true");

        // use mcp with user's per request api key
        TestWebServer.Handler handler = request -> new MockResponse()
                .setBody(MCP_TOOL_CALL_RESPONSE)
                .setHeader("Content-Type", "application/json");
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            ApiKeyData adminAppKey = createAppKey("admin", Map.of(
                    "toolsets/4X25dj1mja51jykqxsXnCH/toolset@",
                    new AutoSharedData(Set.of(ResourceAccessType.READ))));
            adminAppKey.setExecutionPath(List.of("applications/4X25dj1mja51jykqxsXnCH/my-app"));
            apiKeyStore.assignPerRequestApiKey(adminAppKey);

            Response resp = send(HttpMethod.POST, "/v1/toolset/toolsets/4X25dj1mja51jykqxsXnCH/toolset@/mcp", null,
                    MCP_TOOL_CALL_REQUEST, "Content-Type", "application/json", "api-key", adminAppKey.getPerRequestKey());

            assertEquals(200, resp.status());
        }
    }

    @Test
    void testCreateToolSetWithOauthUsingInvalidEndpoint() {
        String requestBody = """
                {
                    "endpoint": "http://this-hostname-does-not-exist.invalid/mcp",
                    "transport": "HTTP",
                    "allowedTools": [],
                    "auth_settings": {
                        "authentication_type": "OAUTH",
                        "redirect_uri": "http://localhost:3000/auth/signin"
                    }
                }
                """;

        try (TestWebServer ignore = new TestWebServer(9876)) {
            Response response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset%201@",
                    null, requestBody, "authorization", "admin");

            assertEquals(400, response.status());
            assertEquals("Connection failed: The specified endpoint 'http://this-hostname-does-not-exist.invalid/mcp' is invalid or unreachable.", response.body());
        }
    }

    @Test
    void testCreateToolSetWithTimeoutOnMetadataDiscovery() {
        String requestBody = """
                {
                    "endpoint": "http://2.1.0.2/mcp",
                    "transport": "HTTP",
                    "allowedTools": [],
                    "auth_settings": {
                        "authentication_type": "OAUTH",
                        "redirect_uri": "http://localhost:3000/auth/signin"
                    }
                }
                """;

        try (TestWebServer ignore = new TestWebServer(9876)) {
            Response response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset%201@",
                    null, requestBody, "authorization", "admin");

            assertEquals(504, response.status());
            assertEquals("HTTP connect timed out", response.body());
        }
    }

    @Test
    void testCreateToolSetWithConnectionErrorOnMetadataDiscovery() {
        String requestBody = """
                {
                    "endpoint": "http://localhost:9999/mcp",
                    "transport": "HTTP",
                    "allowedTools": [],
                    "auth_settings": {
                        "authentication_type": "OAUTH",
                        "redirect_uri": "http://localhost:3000/auth/signin"
                    }
                }
                """;

        try (TestWebServer ignore = new TestWebServer(9876)) {
            Response response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset%201@",
                    null, requestBody, "authorization", "admin");

            assertEquals(502, response.status());
            assertEquals("Cannot connect to http://localhost:9999/mcp", response.body());
        }
    }

    @Test
    void testProxyMcpCallWithToolSetFromConfig() throws JsonProcessingException {
        // getting list of toolsets (ignoring toolsets with invalid key in config)
        Response response = send(HttpMethod.GET, "/openai/toolsets", null, null,  "authorization", "admin");
        verifyJsonNotExact(response, 200, TOOLSETS_LIST_ADMIN_RESPONSE);

        String globalSignInRequestJson = """
                {
                    "url": "my-toolset_2",
                    "credentialsLevel": "GLOBAL",
                    "authenticationType": "API_KEY",
                    "api_key": "Bearer api_key"
                }
                """;

        // non-admin user can't sign in with Global creds
        response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, globalSignInRequestJson, "authorization", "user");
        verify(response, 403, "Forbidden deployment: my-toolset_2");

        // admin can sign in with Global creds
        response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, globalSignInRequestJson, "authorization", "admin");
        verify(response, 200, "true");

        // getting list of toolsets with changed auth status after sign in
        response = send(HttpMethod.GET, "/openai/toolsets", null, null, "authorization", "admin");
        String expectedResponseJson = replaceValueInJsonArray(
                TOOLSETS_LIST_ADMIN_RESPONSE,
                "data",
                "id",
                "my-toolset_2",
                "auth_settings",
                "global_auth_status",
                "SIGNED_IN"
        );
        assertEquals(200, response.status());
        verifyJsonNotExact(expectedResponseJson, response.body());

        TestWebServer.Handler handler = request -> new MockResponse().setBody(MCP_TOOL_CALL_RESPONSE).setHeader("Content-Type", "application/json");
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            ApiKeyData apiKey = createAdminAppKey();
            apiKeyStore.assignPerRequestApiKey(apiKey);

            Response resp = send(HttpMethod.POST, "/v1/toolset/my-toolset_2/mcp", null,
                    MCP_TOOL_CALL_REQUEST, "Content-Type", "application/json", "api-key", apiKey.getPerRequestKey());

            assertEquals(200, resp.status());
            assertEquals(MCP_TOOL_CALL_RESPONSE, resp.body());
        }

        String globalSignOutRequestJson = """
                {
                    "url": "my-toolset_2",
                    "credentialsLevel": "GLOBAL",
                    "authenticationType": "API_KEY"
                }
                """;

        // non-admin user can't sign out with Global creds
        response = send(HttpMethod.POST, "/v1/ops/toolset/signout", null, globalSignOutRequestJson, "authorization", "user");
        verify(response, 403, "Forbidden deployment: my-toolset_2");

        // admin can sign out with Global creds
        response = send(HttpMethod.POST, "/v1/ops/toolset/signout", null, globalSignOutRequestJson, "authorization", "admin");
        verify(response, 200, "true");
    }

    @Test
    void testSignInWithIncorrectAuthType() {
        String globalSignInRequestJson = """
                {
                    "url": "my-toolset_2",
                    "credentialsLevel": "GLOBAL",
                    "authenticationType": "OAUTH",
                    "code": "some-auth-code"
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, globalSignInRequestJson, "authorization", "admin");
        verify(response, 400, "Wrong authentication_type. Expected type: API_KEY, provided: OAUTH");
    }

    @Test
    void testSignOutFromConfigToolsetWithIncorrectAuthType() {
        String globalSignOutRequestJson = """
                {
                    "url": "my-toolset_2",
                    "credentialsLevel": "GLOBAL",
                    "authenticationType": "OAUTH"
                }
                """;

        Response response = send(HttpMethod.POST, "/v1/ops/toolset/signout", null, globalSignOutRequestJson, "authorization", "admin");
        verify(response, 400, "Wrong authentication_type. Expected type: API_KEY, provided: OAUTH");
    }

    @Test
    void testSignOutFromApiCreatedToolsetWithIncorrectAuthType() {
        // create ToolSet with admin JWT
        Response response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset%201@", null,  TOOLSET_CREATE_REQEUST_BODY,
                "authorization", "admin");
        verifyNotExact(response, 200, "\"url\":\"toolsets/4X25dj1mja51jykqxsXnCH/toolset%201@\"");

        // signin into toolset with admin JWT
        response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                {
                    "url": "toolsets/4X25dj1mja51jykqxsXnCH/toolset 1@",
                    "credentialsLevel": "GLOBAL",
                    "authenticationType": "API_KEY",
                    "api_key": "Bearer api_key"
                }
                """, "authorization", "admin");
        verify(response, 200, "true");

        String globalSignOutRequestJson = """
                {
                    "url": "toolsets/4X25dj1mja51jykqxsXnCH/toolset 1@",
                    "credentialsLevel": "GLOBAL",
                    "authenticationType": "OAUTH"
                }
                """;

        response = send(HttpMethod.POST, "/v1/ops/toolset/signout", null, globalSignOutRequestJson, "authorization", "admin");
        verify(response, 400, "Wrong authentication_type. Expected type: API_KEY, provided: OAUTH");
    }

    @Test
    void testCreateToolSetWithOauthStaticEndpointsPreservedAfterDiscovery() throws JsonProcessingException {
        String requestBody = """
                {
                    "endpoint": "http://localhost:9876/mcp",
                    "transport": "HTTP",
                    "allowedTools": [],
                    "auth_settings": {
                        "authentication_type": "OAUTH",
                        "client_id": "my-client-id",
                        "client_secret": "my-client-secret",
                        "redirect_uri": "http://localhost:3000/auth/signin",
                        "authorization_endpoint": "https://static.auth.example.com/authorize",
                        "token_endpoint": "https://static.auth.example.com/token"
                    }
                }
                """;

        String protectedResourceMetadata = """
                {
                    "resource": "http://localhost:9876/mcp",
                    "authorization_servers": ["http://localhost:9876"],
                    "scopes_supported": ["read", "write"]
                }
                """;

        String authServerMetadata = """
                {
                    "issuer": "http://localhost:9876",
                    "authorization_endpoint": "https://discovered.auth.example.com/authorize",
                    "token_endpoint": "https://discovered.auth.example.com/token",
                    "code_challenge_methods_supported": ["S256"]
                }
                """;

        try (TestWebServer server = new TestWebServer(9876)) {
            // MCP endpoint returns 401 without WWW-Authenticate to trigger well-known discovery
            server.map(HttpMethod.POST, "/mcp", 401, "");

            // Protected resource metadata discovery
            server.map(HttpMethod.GET, "/.well-known/oauth-protected-resource/mcp",
                    200, protectedResourceMetadata, "Content-Type", "application/json");

            // Authorization server metadata discovery (on the auth server from authorization_servers)
            server.map(HttpMethod.GET, "/.well-known/oauth-authorization-server",
                    200, authServerMetadata, "Content-Type", "application/json");

            Response response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-oauth-static@",
                    null, requestBody, "authorization", "admin");

            assertEquals(200, response.status());

            // GET the created toolset to verify auth_settings
            response = send(HttpMethod.GET, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-oauth-static@",
                    null, null, "authorization", "admin");

            assertEquals(200, response.status());
            JsonNode toolset = ProxyUtil.MAPPER.readTree(response.body());
            JsonNode authSettings = toolset.get("auth_settings");

            // Static endpoints must be preserved (not overwritten by discovered ones)
            assertEquals("https://static.auth.example.com/authorize", authSettings.get("authorization_endpoint").asText());
            assertEquals("https://static.auth.example.com/token", authSettings.get("token_endpoint").asText());
            // codeChallengeMethod should be filled from discovery
            assertEquals("S256", authSettings.get("code_challenge_method").asText());
        }
    }

    private String replaceValueInJsonArray(
            String originalJson,
            String arrayFieldName,
            String matchField,
            String matchValue,
            String targetObjectField,
            String fieldToUpdate,
            String newValue
    ) throws JsonProcessingException {
        ObjectNode root = (ObjectNode) ProxyUtil.MAPPER.readTree(originalJson);
        ArrayNode arrayNode = (ArrayNode) root.get(arrayFieldName);
        for (JsonNode item : arrayNode) {
            if (matchValue.equals(item.get(matchField).asText())) {
                ObjectNode targetObject = (ObjectNode) item.get(targetObjectField);
                targetObject.put(fieldToUpdate, newValue);
                break;
            }
        }
        return ProxyUtil.MAPPER.writeValueAsString(root);
    }
}
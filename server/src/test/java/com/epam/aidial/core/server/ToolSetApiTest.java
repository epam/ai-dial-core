package com.epam.aidial.core.server;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.data.InvitationLink;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ToolSetApiTest extends ResourceBaseTest {

    private static TestWebServer.Handler mcpToolsHandler(String toolsResponse) {
        return mcpToolsHandler(toolsResponse, "application/json");
    }

    private static TestWebServer.Handler mcpToolsHandler(String toolsResponse, String contentType) {
        return request -> {
            String body = request.getBody().readString(StandardCharsets.UTF_8);
            if ("GET".equals(request.getMethod())) {
                // SSE stream connection - return 405 to signal no streaming support
                return new MockResponse().setResponseCode(405);
            }
            if (body.contains("\"method\":\"initialize\"") || body.contains("\"method\": \"initialize\"")) {
                String id = extractJsonRpcId(body);
                String initResponse = """
                        {"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"test-server","version":"1.0"}}}
                        """.formatted(id);
                return new MockResponse()
                        .setBody(initResponse)
                        .setHeader("Content-Type", "application/json");
            } else if (body.contains("\"method\":\"notifications/initialized\"")
                    || body.contains("\"method\": \"notifications/initialized\"")) {
                return new MockResponse().setResponseCode(202)
                        .setHeader("Content-Type", "application/json");
            } else if (body.contains("\"method\":\"tools/list\"") || body.contains("\"method\": \"tools/list\"")) {
                String id = extractJsonRpcId(body);
                String adjustedResponse = toolsResponse.replaceFirst("\"id\"\\s*:\\s*\\d+", "\"id\":" + id);
                return new MockResponse()
                        .setBody(adjustedResponse)
                        .setHeader("Content-Type", contentType);
            }
            return new MockResponse().setResponseCode(400).setBody("Unknown method");
        };
    }

    private static String extractJsonRpcId(String body) {
        try {
            JsonNode node = ProxyUtil.MAPPER.readTree(body);
            JsonNode idNode = node.get("id");
            if (idNode == null) {
                return "null";
            }
            if (idNode.isTextual()) {
                return "\"" + idNode.asText() + "\"";
            }
            return idNode.toString();
        } catch (Exception e) {
            return "1";
        }
    }

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
                           "assistant_attachments_in_request": false,
                           "mcp" : true
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
                          "assistant_attachments_in_request" : false,
                          "mcp" : true
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
                      },
                    {
                        "id" : "oauth-toolset",
                        "toolset" : "oauth-toolset",
                        "display_name" : "OAuth Toolset",
                        "reference" : "oauth-toolset",
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
                          "assistant_attachments_in_request" : false,
                          "mcp" : true
                        },
                        "description_keywords" : [ ],
                        "max_retry_attempts" : 1,
                        "auth_settings" : {
                          "authentication_type" : "OAUTH",
                          "client_id" : "test-client-id",
                          "authorization_endpoint" : "http://localhost:9876/authorize",
                          "redirect_uri" : "http://localhost:3000/auth/signin",
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
                           "assistant_attachments_in_request": false,
                           "mcp" : true
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
                   "responses_defaults" : { },
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
                        "assistant_attachments_in_request": false,
                        "mcp" : true
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
                            "assistant_attachments_in_request": false,
                            "mcp" : true
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
                            "assistant_attachments_in_request": false,
                            "mcp" : true
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
    void testProxyMcpPostCall_ListTools_GzippedResponse() throws JsonProcessingException {
        // Regression for the gzip MCP proxy bug: a CDN-fronted MCP server may gzip the tools/list
        // response regardless of Accept-Encoding. Core must decompress before filtering, and must
        // not leave the re-serialized (plain) body labeled Content-Encoding: gzip. Without the fix
        // Core fails JSON parsing on the raw gzip bytes and returns 400 "Invalid response body from
        // MCP server"; without the header removal the client cannot decode the response.
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
        TestWebServer.Handler handler = request -> gzippedJsonResponse(mcpResponse);
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            // "git" toolset has allowedTools: ["branch", "remote"]
            Response resp = send(HttpMethod.POST, "/v1/toolset/git/mcp", null,
                    mcpRequest, "Content-Type", "application/json");

            assertEquals(200, resp.status(),
                    () -> "expected gzipped tools/list to be decoded and filtered but got: " + resp.body());
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
    void testProxyMcpPostCall_CallDisallowedTool() {
        String mcpRequest = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "tools/call",
                    "params": {
                        "name": "tag",
                        "arguments": {}
                    }
                }
                """;
        String mcpResponse = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "result": {
                        "content": [{ "type": "text", "text": "done" }]
                    }
                }
                """;
        TestWebServer.Handler handler = request -> new MockResponse()
                .setBody(mcpResponse).setHeader("Content-Type", "application/json");
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            // "git" toolset has allowed_tools: ["branch", "remote"], so "tag" should be rejected
            Response resp = send(HttpMethod.POST, "/v1/toolset/git/mcp", null,
                    mcpRequest, "Content-Type", "application/json");
            assertEquals(403, resp.status());
        }
    }

    @Test
    void testProxyMcpPostCall_CallAllowedTool() {
        String mcpRequest = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "tools/call",
                    "params": {
                        "name": "branch",
                        "arguments": {}
                    }
                }
                """;
        String mcpResponse = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "result": {
                        "content": [{ "type": "text", "text": "done" }]
                    }
                }
                """;
        TestWebServer.Handler handler = request -> new MockResponse()
                .setBody(mcpResponse).setHeader("Content-Type", "application/json");
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            // "git" toolset has allowed_tools: ["branch", "remote"], so "branch" should be allowed
            Response resp = send(HttpMethod.POST, "/v1/toolset/git/mcp", null,
                    mcpRequest, "Content-Type", "application/json");
            assertEquals(200, resp.status());
            assertEquals(mcpResponse, resp.body());
        }
    }

    @Test
    void testProxyMcpPostCall_CallDisallowedToolAsAdmin() {
        String mcpRequest = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "tools/call",
                    "params": {
                        "name": "tag",
                        "arguments": {}
                    }
                }
                """;
        String mcpResponse = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "result": {
                        "content": [{ "type": "text", "text": "done" }]
                    }
                }
                """;
        TestWebServer.Handler handler = request -> new MockResponse()
                .setBody(mcpResponse).setHeader("Content-Type", "application/json");
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            // admin with useAllowedTools=false can bypass allowedTools enforcement
            Response resp = send(HttpMethod.POST, "/v1/toolset/git/mcp?useAllowedTools=false", null,
                    mcpRequest, "Content-Type", "application/json", "authorization", "admin");
            assertEquals(200, resp.status());
            assertEquals(mcpResponse, resp.body());
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
                    "responses_defaults" : { },
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
    void testUserLevelSignInAndSignOutWithApiKeyAuthenticatedCaller() {
        // Regression for #1470: USER-level sign-in/sign-out must work when the caller is authenticated
        // by an API key (not JWT). Previously context.getUserId() was null for API-key callers,
        // which stored null into ResourceCredentials.userId and later NPE'd on refresh/sign-out/status.
        String bucket = "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST"; // proxyKey1's bucket (project EPM-RTC-GPT)

        // create toolset owned by the API key
        Response response = send(HttpMethod.PUT, "/v1/toolsets/" + bucket + "/toolset%201@", null,
                TOOLSET_CREATE_REQEUST_BODY, "api-key", "proxyKey1");
        verifyNotExact(response, 200, "\"url\":\"toolsets/" + bucket + "/toolset%201@\"");

        // USER-level sign-in authenticated by the API key (pre-fix: stored userId=null)
        response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                {
                    "url": "toolsets/%s/toolset 1@",
                    "credentialsLevel": "USER",
                    "authenticationType": "API_KEY",
                    "api_key": "Bearer api_key"
                }
                """.formatted(bucket), "api-key", "proxyKey1");
        verify(response, 200, "true");

        // GET the toolset via the same API key - user_level_auth_status should be SIGNED_IN.
        // Pre-fix: ResourceAuthSettingsService.setUserAuthStatus NPE'd on userId.equals(...) when userId was null.
        response = send(HttpMethod.GET, "/v1/toolsets/" + bucket + "/toolset%201@", null, null,
                "api-key", "proxyKey1");
        verifyNotExact(response, 200, "\"user_level_auth_status\":\"SIGNED_IN\"");

        // USER-level sign-out authenticated by the same API key.
        // Pre-fix: validateDeleteOperation NPE'd on Objects.requireNonNull(existingCredentialsUserId).
        response = send(HttpMethod.POST, "/v1/ops/toolset/signout", null, """
                {
                    "url": "toolsets/%s/toolset 1@",
                    "credentialsLevel": "USER",
                    "authenticationType": "API_KEY"
                }
                """.formatted(bucket), "api-key", "proxyKey1");
        verify(response, 200, "true");

        // Verify the toolset reports SIGNED_OUT after sign-out.
        response = send(HttpMethod.GET, "/v1/toolsets/" + bucket + "/toolset%201@", null, null,
                "api-key", "proxyKey1");
        verifyNotExact(response, 200, "\"user_level_auth_status\":\"SIGNED_OUT\"");
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

    @Test
    void testCreateToolSetWithOauthWhenDiscoveryResponseIsGzipped() {
        // Regression test for #1493: CDN-fronted OAuth discovery endpoints (e.g., Akamai) return
        // Content-Encoding: gzip. ResourceAuthorizationClient must handle compressed responses.
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
            server.map(HttpMethod.POST, "/mcp", 401, "");
            // Simulate a CDN (e.g., Akamai) that always gzips JSON responses regardless of
            // the client's Accept-Encoding preference. The fix must decompress on receive.
            server.map(HttpMethod.GET, "/.well-known/oauth-protected-resource/mcp",
                    gzippedJsonResponse(protectedResourceMetadata));
            server.map(HttpMethod.GET, "/.well-known/oauth-authorization-server",
                    gzippedJsonResponse(authServerMetadata));

            Response response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-oauth-gzip@",
                    null, requestBody, "authorization", "admin");

            assertEquals(200, response.status(), "PUT should succeed when OAuth discovery response is gzipped");
        }
    }

    private static MockResponse gzippedJsonResponse(String body) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        MockResponse response = new MockResponse();
        response.setResponseCode(200);
        response.setBody(new Buffer().write(baos.toByteArray()));
        response.setHeader("Content-Type", "application/json");
        response.setHeader("Content-Encoding", "gzip");
        return response;
    }

    @Test
    void testUpdateOauthToolSetRegeneratesPkceWhenCodeChallengeMethodChanges() throws JsonProcessingException {
        String createRequestBody = """
                {
                    "endpoint": "http://localhost:9876/mcp",
                    "transport": "HTTP",
                    "allowedTools": [],
                    "auth_settings": {
                        "authentication_type": "OAUTH",
                        "client_id": "my-client-id",
                        "client_secret": "my-client-secret",
                        "redirect_uri": "http://localhost:3000/auth/signin",
                        "authorization_endpoint": "https://auth.example.com/authorize",
                        "token_endpoint": "https://auth.example.com/token"
                    }
                }
                """;

        String authServerMetadata = """
                {
                    "issuer": "http://localhost:9876",
                    "authorization_endpoint": "https://auth.example.com/authorize",
                    "token_endpoint": "https://auth.example.com/token",
                    "code_challenge_methods_supported": ["S256", "plain"]
                }
                """;

        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/mcp", 401, "");
            server.map(HttpMethod.GET, "/.well-known/oauth-authorization-server",
                    200, authServerMetadata, "Content-Type", "application/json");

            // Create with S256 (picked from discovery as preferred)
            Response response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-pkce@",
                    null, createRequestBody, "authorization", "admin");
            assertEquals(200, response.status());

            response = send(HttpMethod.GET, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-pkce@",
                    null, null, "authorization", "admin");
            assertEquals(200, response.status());

            JsonNode authSettings = ProxyUtil.MAPPER.readTree(response.body()).get("auth_settings");
            assertEquals("S256", authSettings.get("code_challenge_method").asText());
            String initialChallenge = authSettings.get("code_challenge").asText();
            assertNotNull(initialChallenge);
            assertFalse(initialChallenge.isEmpty());

            // Update the same toolset, switching code_challenge_method to plain
            String updateRequestBody = """
                    {
                        "endpoint": "http://localhost:9876/mcp",
                        "transport": "HTTP",
                        "allowedTools": [],
                        "auth_settings": {
                            "authentication_type": "OAUTH",
                            "client_id": "my-client-id",
                            "redirect_uri": "http://localhost:3000/auth/signin",
                            "authorization_endpoint": "https://auth.example.com/authorize",
                            "token_endpoint": "https://auth.example.com/token",
                            "code_challenge_method": "plain"
                        }
                    }
                    """;
            response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-pkce@",
                    null, updateRequestBody, "authorization", "admin");
            assertEquals(200, response.status());

            response = send(HttpMethod.GET, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-pkce@",
                    null, null, "authorization", "admin");
            assertEquals(200, response.status());

            authSettings = ProxyUtil.MAPPER.readTree(response.body()).get("auth_settings");
            assertEquals("plain", authSettings.get("code_challenge_method").asText());
            String updatedChallenge = authSettings.get("code_challenge").asText();
            assertNotNull(updatedChallenge);
            assertFalse(updatedChallenge.isEmpty());
            assertNotEquals(initialChallenge, updatedChallenge);

            // Re-update without changing method - challenge must be preserved
            response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-pkce@",
                    null, updateRequestBody, "authorization", "admin");
            assertEquals(200, response.status());

            response = send(HttpMethod.GET, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-pkce@",
                    null, null, "authorization", "admin");
            assertEquals(200, response.status());

            authSettings = ProxyUtil.MAPPER.readTree(response.body()).get("auth_settings");
            assertEquals("plain", authSettings.get("code_challenge_method").asText());
            assertEquals(updatedChallenge, authSettings.get("code_challenge").asText());
        }
    }

    @Test
    void testOauthSignInWithRedirectUriFromRequest() {
        String tokenResponse = """
                {
                    "access_token": "test-access-token",
                    "refresh_token": "test-refresh-token",
                    "expires_in": 3600
                }
                """;

        try (TestWebServer server = new TestWebServer(9876)) {
            // MCP endpoint returns 401 to trigger metadata discovery
            server.map(HttpMethod.POST, "/mcp", 401, "");
            // No metadata endpoints — discovery will fail gracefully, static settings will be used

            // Token endpoint that validates redirect_uri from the request
            server.map(HttpMethod.POST, "/token", request -> {

                String body = request.getBody().readUtf8();
                if (body.contains("redirect_uri=http%3A%2F%2Fchat%2Fcallback")) {
                    return new MockResponse()
                            .setBody(tokenResponse)
                            .setHeader("Content-Type", "application/json");
                }
                return new MockResponse().setResponseCode(400).setBody("redirect_uri mismatch");
            });

            // Create toolset with OAuth settings (single redirect_uri for admin)
            Response response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-oauth@", null, """
                    {
                        "endpoint": "http://localhost:9876/mcp",
                        "transport": "HTTP",
                        "allowedTools": [],
                        "auth_settings": {
                            "authentication_type": "OAUTH",
                            "client_id": "my-client-id",
                            "client_secret": "my-client-secret",
                            "redirect_uri": "http://admin/callback",
                            "authorization_endpoint": "http://localhost:9876/authorize",
                            "token_endpoint": "http://localhost:9876/token"
                        }
                    }
                    """, "authorization", "admin");
            assertEquals(200, response.status());

            // Sign in with redirect_uri from the chat (allowed via global allowedRedirectUris in settings)
            response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "toolsets/4X25dj1mja51jykqxsXnCH/toolset-oauth@",
                        "credentialsLevel": "GLOBAL",
                        "authenticationType": "OAUTH",
                        "code": "auth-code",
                        "redirect_uri": "http://chat/callback"
                    }
                    """, "authorization", "admin");
            verify(response, 200, "true");
        }
    }

    @Test
    void testOauthSignInWithDisallowedRedirectUri() {
        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/mcp", 401, "");

            // Create toolset with single redirect_uri
            Response response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-oauth-reject@", null, """
                    {
                        "endpoint": "http://localhost:9876/mcp",
                        "transport": "HTTP",
                        "allowedTools": [],
                        "auth_settings": {
                            "authentication_type": "OAUTH",
                            "client_id": "my-client-id",
                            "client_secret": "my-client-secret",
                            "redirect_uri": "http://admin/callback",
                            "authorization_endpoint": "http://localhost:9876/authorize",
                            "token_endpoint": "http://localhost:9876/token"
                        }
                    }
                    """, "authorization", "admin");
            assertEquals(200, response.status());

            // Sign in with redirect_uri not in allowed list — should be rejected
            response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "toolsets/4X25dj1mja51jykqxsXnCH/toolset-oauth-reject@",
                        "credentialsLevel": "GLOBAL",
                        "authenticationType": "OAUTH",
                        "code": "auth-code",
                        "redirect_uri": "http://evil/callback"
                    }
                    """, "authorization", "admin");
            assertEquals(400, response.status());
        }
    }

    @Test
    void testOauthSignInWithoutRedirectUriFallsBackToSettings() {
        String tokenResponse = """
                {
                    "access_token": "test-access-token",
                    "refresh_token": "test-refresh-token",
                    "expires_in": 3600
                }
                """;

        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/mcp", 401, "");

            // Token endpoint that validates redirect_uri from auth settings
            server.map(HttpMethod.POST, "/token", request -> {
                String body = request.getBody().readUtf8();
                if (body.contains("redirect_uri=http%3A%2F%2Fadmin%2Fcallback")) {
                    return new MockResponse()
                            .setBody(tokenResponse)
                            .setHeader("Content-Type", "application/json");
                }
                return new MockResponse().setResponseCode(400).setBody("redirect_uri mismatch");
            });

            // Create toolset with OAuth settings
            Response response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-oauth-fallback@", null, """
                    {
                        "endpoint": "http://localhost:9876/mcp",
                        "transport": "HTTP",
                        "allowedTools": [],
                        "auth_settings": {
                            "authentication_type": "OAUTH",
                            "client_id": "my-client-id",
                            "client_secret": "my-client-secret",
                            "redirect_uri": "http://admin/callback",
                            "authorization_endpoint": "http://localhost:9876/authorize",
                            "token_endpoint": "http://localhost:9876/token"
                        }
                    }
                    """, "authorization", "admin");
            assertEquals(200, response.status());

            // Sign in without redirect_uri — should fall back to auth settings
            response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "toolsets/4X25dj1mja51jykqxsXnCH/toolset-oauth-fallback@",
                        "credentialsLevel": "GLOBAL",
                        "authenticationType": "OAUTH",
                        "code": "auth-code"
                    }
                    """, "authorization", "admin");
            verify(response, 200, "true");
        }
    }

    @Test
    void testOauthSignInWithClientSecretBasic() {
        // Snowflake-managed MCP (and any RFC 6749 §2.3.1-strict authorization server) requires
        // HTTP Basic auth at the token endpoint. DCR advertises this via
        // token_endpoint_auth_method=client_secret_basic; DIAL must honor it on token exchange.
        String tokenResponse = """
                {
                    "access_token": "test-access-token",
                    "refresh_token": "test-refresh-token",
                    "expires_in": 3600
                }
                """;
        java.util.concurrent.atomic.AtomicReference<String> authHeaderRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> bodyRef = new java.util.concurrent.atomic.AtomicReference<>();

        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/mcp", 401, "");
            server.map(HttpMethod.POST, "/token", request -> {
                authHeaderRef.set(request.getHeader("Authorization"));
                bodyRef.set(request.getBody().readUtf8());
                String expected = "Basic " + java.util.Base64.getEncoder().encodeToString(
                        "my-client-id:my-client-secret".getBytes(StandardCharsets.UTF_8));
                if (expected.equals(authHeaderRef.get())) {
                    return new MockResponse().setBody(tokenResponse).setHeader("Content-Type", "application/json");
                }
                return new MockResponse().setResponseCode(400)
                        .setBody("{\"error\":\"invalid_client\"}")
                        .setHeader("Content-Type", "application/json");
            });

            Response response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-basic@", null, """
                    {
                        "endpoint": "http://localhost:9876/mcp",
                        "transport": "HTTP",
                        "allowedTools": [],
                        "auth_settings": {
                            "authentication_type": "OAUTH",
                            "client_id": "my-client-id",
                            "client_secret": "my-client-secret",
                            "redirect_uri": "http://admin/callback",
                            "authorization_endpoint": "http://localhost:9876/authorize",
                            "token_endpoint": "http://localhost:9876/token",
                            "token_endpoint_auth_method": "client_secret_basic"
                        }
                    }
                    """, "authorization", "admin");
            assertEquals(200, response.status());

            response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "toolsets/4X25dj1mja51jykqxsXnCH/toolset-basic@",
                        "credentialsLevel": "GLOBAL",
                        "authenticationType": "OAUTH",
                        "code": "auth-code"
                    }
                    """, "authorization", "admin");
            verify(response, 200, "true");

            String expectedHeader = "Basic " + java.util.Base64.getEncoder().encodeToString(
                    "my-client-id:my-client-secret".getBytes(StandardCharsets.UTF_8));
            assertEquals(expectedHeader, authHeaderRef.get(),
                    "client_secret_basic must produce an Authorization: Basic header per RFC 6749 §2.3.1");
            assertTrue(bodyRef.get().contains("client_id="),
                    "client_id must appear in body for client_secret_basic (RFC 6749 §3.2.1), got: " + bodyRef.get());
            assertFalse(bodyRef.get().contains("client_secret="),
                    "client_secret must NOT appear in body for client_secret_basic (secret stays in the header), got: " + bodyRef.get());

            // Persisted setting is readable via GET (round-trip)
            response = send(HttpMethod.GET, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-basic@",
                    null, null, "authorization", "admin");
            assertEquals(200, response.status());
            JsonNode authSettings = ProxyUtil.MAPPER.readTree(response.body()).get("auth_settings");
            assertEquals("client_secret_basic", authSettings.get("token_endpoint_auth_method").asText());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testOauthSignInWithoutTokenEndpointAuthMethod_defaultsToBasicPerSpec() {
        // Toolsets with token_endpoint_auth_method unset (legacy data or operator omitted the
        // field) default to client_secret_basic per RFC 6749 §2.3.1 — BASIC is MUST-support for
        // compliant authorization servers; POST is NOT RECOMMENDED. OAuth 2.1 deprecates POST.
        String tokenResponse = """
                {
                    "access_token": "test-access-token",
                    "refresh_token": "test-refresh-token",
                    "expires_in": 3600
                }
                """;
        java.util.concurrent.atomic.AtomicReference<String> authHeaderRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> bodyRef = new java.util.concurrent.atomic.AtomicReference<>();

        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/mcp", 401, "");
            server.map(HttpMethod.POST, "/token", request -> {
                authHeaderRef.set(request.getHeader("Authorization"));
                bodyRef.set(request.getBody().readUtf8());
                return new MockResponse().setBody(tokenResponse).setHeader("Content-Type", "application/json");
            });

            // No token_endpoint_auth_method in payload — simulates a pre-existing toolset.
            Response response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-legacy@", null, """
                    {
                        "endpoint": "http://localhost:9876/mcp",
                        "transport": "HTTP",
                        "allowedTools": [],
                        "auth_settings": {
                            "authentication_type": "OAUTH",
                            "client_id": "my-client-id",
                            "client_secret": "my-client-secret",
                            "redirect_uri": "http://admin/callback",
                            "authorization_endpoint": "http://localhost:9876/authorize",
                            "token_endpoint": "http://localhost:9876/token"
                        }
                    }
                    """, "authorization", "admin");
            assertEquals(200, response.status());

            response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "toolsets/4X25dj1mja51jykqxsXnCH/toolset-legacy@",
                        "credentialsLevel": "GLOBAL",
                        "authenticationType": "OAUTH",
                        "code": "auth-code"
                    }
                    """, "authorization", "admin");
            verify(response, 200, "true");

            String expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
                    "my-client-id:my-client-secret".getBytes(StandardCharsets.UTF_8));
            assertEquals(expectedAuth, authHeaderRef.get(),
                    "null token_endpoint_auth_method must default to client_secret_basic");
            assertTrue(bodyRef.get().contains("client_id="),
                    "client_id must be in body when defaulting to basic (RFC 6749 §3.2.1), got: " + bodyRef.get());
            assertFalse(bodyRef.get().contains("client_secret="),
                    "client_secret must NOT be in body when defaulting to basic, got: " + bodyRef.get());
        }
    }

    @Test
    void testOauthDcrPublicClient_sendsClientIdInBodyNotBasicHeader() {
        // Reproduces the FastMCP "OAuth Proxy" regression. A dynamically-registered PUBLIC client
        // (DCR response carries NO client_secret) must authenticate at the token endpoint with
        // token_endpoint_auth_method=none — i.e. client_id in the BODY, no Authorization header.
        // DIAL defaulted a secretless client to client_secret_basic, which moves client_id into a
        // Basic header and drops it from the body; FastMCP then rejects the token exchange with
        // invalid_grant "Client ID does not match the one used in the initial request."
        // EXPECTED (post-fix): a secretless DCR client uses 'none' -> client_id in body, no header.
        String protectedResourceMetadata = """
                {
                    "resource": "http://localhost:9876/mcp",
                    "authorization_servers": ["http://localhost:9876"],
                    "scopes_supported": ["read"]
                }
                """;
        String authServerMetadata = """
                {
                    "issuer": "http://localhost:9876",
                    "authorization_endpoint": "http://localhost:9876/authorize",
                    "token_endpoint": "http://localhost:9876/token",
                    "registration_endpoint": "http://localhost:9876/register",
                    "code_challenge_methods_supported": ["S256"]
                }
                """;
        // FastMCP-style DCR response: a client_id, NO client_secret, and (like FastMCP) no explicit
        // token_endpoint_auth_method — the registered client is public.
        String registrationResponse = """
                {
                    "client_id": "dcr-public-client",
                    "client_name": "toolset-dcr-public",
                    "redirect_uris": ["http://admin/callback"]
                }
                """;
        String tokenResponse = """
                {
                    "access_token": "test-access-token",
                    "refresh_token": "test-refresh-token",
                    "expires_in": 3600
                }
                """;
        java.util.concurrent.atomic.AtomicReference<String> authHeaderRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> bodyRef = new java.util.concurrent.atomic.AtomicReference<>();

        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/mcp", 401, "");
            server.map(HttpMethod.GET, "/.well-known/oauth-protected-resource/mcp",
                    200, protectedResourceMetadata, "Content-Type", "application/json");
            server.map(HttpMethod.GET, "/.well-known/oauth-authorization-server",
                    200, authServerMetadata, "Content-Type", "application/json");
            server.map(HttpMethod.POST, "/register",
                    200, registrationResponse, "Content-Type", "application/json");
            server.map(HttpMethod.POST, "/token", request -> {
                authHeaderRef.set(request.getHeader("Authorization"));
                bodyRef.set(request.getBody().readUtf8());
                return new MockResponse().setBody(tokenResponse).setHeader("Content-Type", "application/json");
            });

            // Dynamic client registration: no client_id/client_secret supplied.
            Response response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-dcr-public@", null, """
                    {
                        "endpoint": "http://localhost:9876/mcp",
                        "transport": "HTTP",
                        "allowedTools": [],
                        "auth_settings": {
                            "authentication_type": "OAUTH",
                            "redirect_uri": "http://admin/callback"
                        }
                    }
                    """, "authorization", "admin");
            assertEquals(200, response.status());

            response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "toolsets/4X25dj1mja51jykqxsXnCH/toolset-dcr-public@",
                        "credentialsLevel": "GLOBAL",
                        "authenticationType": "OAUTH",
                        "code": "auth-code"
                    }
                    """, "authorization", "admin");
            verify(response, 200, "true");

            // Public client (no secret) => token_endpoint_auth_method=none: client_id in the BODY,
            // no Authorization header. Pre-fix DIAL sent a Basic header and omitted body client_id.
            assertNull(authHeaderRef.get(),
                    "public DCR client (no secret) must NOT send an Authorization header, got: " + authHeaderRef.get());
            assertTrue(bodyRef.get().contains("client_id=dcr-public-client"),
                    "public client must send client_id in the body, got: " + bodyRef.get());
            assertFalse(bodyRef.get().contains("client_secret="),
                    "no client_secret should be sent for a public client, got: " + bodyRef.get());
        }
    }

    @Test
    void testOauthUpdate_PreservesTokenEndpointAuthMethodWhenOmitted() {
        // A PATCH-style PUT that omits token_endpoint_auth_method must NOT silently downgrade a
        // toolset previously configured for client_secret_basic — otherwise the Snowflake regression
        // this PR fixes would reappear on the very next admin save.
        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/mcp", 401, "");

            Response create = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-preserve@", null, """
                    {
                        "endpoint": "http://localhost:9876/mcp",
                        "transport": "HTTP",
                        "allowedTools": [],
                        "auth_settings": {
                            "authentication_type": "OAUTH",
                            "client_id": "my-client-id",
                            "client_secret": "my-client-secret",
                            "redirect_uri": "http://admin/callback",
                            "authorization_endpoint": "http://localhost:9876/authorize",
                            "token_endpoint": "http://localhost:9876/token",
                            "token_endpoint_auth_method": "client_secret_basic"
                        }
                    }
                    """, "authorization", "admin");
            assertEquals(200, create.status());

            Response update = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-preserve@", null, """
                    {
                        "endpoint": "http://localhost:9876/mcp",
                        "transport": "HTTP",
                        "allowedTools": [],
                        "auth_settings": {
                            "authentication_type": "OAUTH",
                            "client_id": "my-client-id",
                            "redirect_uri": "http://admin/callback",
                            "authorization_endpoint": "http://localhost:9876/authorize",
                            "token_endpoint": "http://localhost:9876/token"
                        }
                    }
                    """, "authorization", "admin");
            assertEquals(200, update.status());

            Response get = send(HttpMethod.GET, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/toolset-preserve@",
                    null, null, "authorization", "admin");
            assertEquals(200, get.status());
            JsonNode authSettings = ProxyUtil.MAPPER.readTree(get.body()).get("auth_settings");
            assertEquals("client_secret_basic", authSettings.get("token_endpoint_auth_method").asText(),
                    "token_endpoint_auth_method must be preserved when omitted on update");
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testStoredOauthToolsetSendsPlaintextClientSecretOnRefresh() {
        // Resource-stored toolsets persist authSettings.clientSecret encrypted at rest. The MCP
        // proxy controller used to forward toolset.getAuthSettings() to the credential refresh
        // path WITHOUT decrypting first, so the auth server received Base64-wrapped ciphertext
        // as client_secret and rejected with invalid_client. Reproduces and guards the fix.
        String signInTokenResponse = """
                {
                    "access_token": "expired-access-token",
                    "refresh_token": "valid-refresh-token",
                    "expires_in": 0
                }
                """;
        String refreshedTokenResponse = """
                {
                    "access_token": "refreshed-access-token",
                    "refresh_token": "valid-refresh-token",
                    "expires_in": 3600
                }
                """;
        String plaintextClientSecret = "plaintext-client-secret";
        java.util.concurrent.atomic.AtomicReference<String> refreshBody = new java.util.concurrent.atomic.AtomicReference<>();

        try (TestWebServer server = new TestWebServer(9876)) {
            // Mirror real OAuth servers (e.g. Notion): reject refresh requests whose client_secret
            // doesn't match the registered value with 401 invalid_client.
            server.map(HttpMethod.POST, "/token", request -> {
                String body = request.getBody().readUtf8();
                if (body.contains("grant_type=refresh_token")) {
                    refreshBody.set(body);
                    if (!body.contains("client_secret=" + plaintextClientSecret)) {
                        return new MockResponse()
                                .setResponseCode(401)
                                .setBody("{\"error\":\"invalid_client\",\"error_description\":\"Invalid client_secret\"}")
                                .setHeader("Content-Type", "application/json");
                    }
                    return new MockResponse()
                            .setBody(refreshedTokenResponse)
                            .setHeader("Content-Type", "application/json");
                }
                return new MockResponse()
                        .setBody(signInTokenResponse)
                        .setHeader("Content-Type", "application/json");
            });
            server.map(HttpMethod.POST, "/mcp", request ->
                    new MockResponse()
                            .setBody(MCP_TOOL_CALL_RESPONSE)
                            .setHeader("Content-Type", "application/json"));

            // Explicit POST so refresh sends client_secret in the body — this test verifies the
            // stored secret is decrypted before being forwarded, not the default auth method.
            Response response = send(HttpMethod.PUT,
                    "/v1/toolsets/4X25dj1mja51jykqxsXnCH/notion-toolset@", null, """
                            {
                                "endpoint": "http://localhost:9876/mcp",
                                "transport": "HTTP",
                                "allowedTools": [],
                                "auth_settings": {
                                    "authentication_type": "OAUTH",
                                    "client_id": "stored-client-id",
                                    "client_secret": "%s",
                                    "redirect_uri": "http://admin/callback",
                                    "authorization_endpoint": "http://localhost:9876/authorize",
                                    "token_endpoint": "http://localhost:9876/token",
                                    "token_endpoint_auth_method": "client_secret_post"
                                }
                            }
                            """.formatted(plaintextClientSecret), "authorization", "admin");
            assertEquals(200, response.status());

            response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "toolsets/4X25dj1mja51jykqxsXnCH/notion-toolset@",
                        "credentialsLevel": "GLOBAL",
                        "authenticationType": "OAUTH",
                        "code": "auth-code"
                    }
                    """, "authorization", "admin");
            verify(response, 200, "true");

            ApiKeyData apiKey = createAppKey("admin", Map.of(
                    "toolsets/4X25dj1mja51jykqxsXnCH/notion-toolset@",
                    new AutoSharedData(Set.of(ResourceAccessType.READ))));
            apiKey.setExecutionPath(List.of("applications/4X25dj1mja51jykqxsXnCH/my-app"));
            apiKeyStore.assignPerRequestApiKey(apiKey);

            Response mcpResponse = send(HttpMethod.POST,
                    "/v1/toolset/toolsets/4X25dj1mja51jykqxsXnCH/notion-toolset@/mcp", null,
                    MCP_TOOL_CALL_REQUEST, "Content-Type", "application/json", "api-key", apiKey.getPerRequestKey());
            assertEquals(200, mcpResponse.status(),
                    "MCP call should succeed when stored client_secret is decrypted before refresh, got: " + mcpResponse.status());

            String capturedRefreshBody = refreshBody.get();
            assertNotNull(capturedRefreshBody, "Token refresh should have been triggered (expires_in=0 on sign-in)");
            assertTrue(capturedRefreshBody.contains("client_secret=" + plaintextClientSecret),
                    "Refresh request must send plaintext client_secret, got: " + capturedRefreshBody);

            response = send(HttpMethod.GET,
                    "/openai/toolsets/toolsets/4X25dj1mja51jykqxsXnCH/notion-toolset@", null, null, "authorization", "admin");
            assertEquals(200, response.status());
            assertTrue(response.body().contains("\"global_auth_status\":\"SIGNED_IN\""),
                    "Toolset should remain SIGNED_IN after a successful refresh, got: " + response.body());
        }
    }

    @Test
    void testStaticOauthToolsetSecretPreservedAfterListing() {
        String tokenResponse = """
                {
                    "access_token": "test-access-token",
                    "refresh_token": "test-refresh-token",
                    "expires_in": 3600
                }
                """;

        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/token", request ->
                    new MockResponse()
                            .setBody(tokenResponse)
                            .setHeader("Content-Type", "application/json"));

            // list toolsets multiple times as admin (previously this would null out clientSecret via clearAuthSettings)
            for (int i = 0; i < 3; i++) {
                Response response = send(HttpMethod.GET, "/openai/toolsets", null, null, "authorization", "admin");
                assertEquals(200, response.status());
                // verify secrets are not leaked in list response
                assertFalse(response.body().contains("test-client-secret"), "client_secret must not be in list response");
                assertFalse(response.body().contains("code_verifier"), "code_verifier must not be in list response");
            }

            // get specific toolset (previously this would also null out clientSecret)
            Response response = send(HttpMethod.GET, "/openai/toolsets/oauth-toolset", null, null, "authorization", "admin");
            assertEquals(200, response.status());
            // verify secrets are not leaked in get response
            assertFalse(response.body().contains("test-client-secret"), "client_secret must not be in get response");
            assertFalse(response.body().contains("code_verifier"), "code_verifier must not be in get response");

            // sign in should still work - client_secret must not have been cleared by listing
            response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "oauth-toolset",
                        "credentialsLevel": "GLOBAL",
                        "authenticationType": "OAUTH",
                        "code": "auth-code"
                    }
                    """, "authorization", "admin");
            verify(response, 200, "true");
        }
    }

    @Test
    void testGetAllTools_AdminAccess() throws JsonProcessingException {
        String mcpToolsListResponse = """
                {
                   "jsonrpc": "2.0",
                   "id": 2,
                   "result": {
                     "tools": [
                       {"name": "branch", "description": "Manage branches"},
                       {"name": "tag", "description": "Manage tags"},
                       {"name": "remote", "description": "Manage remotes"}
                     ]
                   }
                 }
                """;
        try (TestWebServer ignore = new TestWebServer(9876, mcpToolsHandler(mcpToolsListResponse))) {
            Response resp = send(HttpMethod.GET, "/v1/toolset/git/tools",
                    null, null, "authorization", "admin");

            assertEquals(200, resp.status());
            var json = ProxyUtil.MAPPER.readTree(resp.body());
            ArrayNode tools = Optional.ofNullable(json.get("tools"))
                    .map(node -> (ArrayNode) node)
                    .orElse(ProxyUtil.MAPPER.createArrayNode());
            assertEquals(3, tools.size());
            assertEquals("branch", tools.get(0).get("name").asText());
            assertEquals("tag", tools.get(1).get("name").asText());
            assertEquals("remote", tools.get(2).get("name").asText());
        }
    }

    @Test
    void testGetAllTools_RegularUserForbidden() {
        try (TestWebServer ignore = new TestWebServer(9876, mcpToolsHandler("{}"))) {
            Response resp = send(HttpMethod.GET, "/v1/toolset/git/tools");

            assertEquals(403, resp.status());
        }
    }

    @Test
    void testGetAllowedTools_JsonResponse() throws JsonProcessingException {
        String mcpToolsListResponse = """
                {
                   "jsonrpc": "2.0",
                   "id": 2,
                   "result": {
                     "tools": [
                       {"name": "branch", "description": "Manage branches"},
                       {"name": "tag", "description": "Manage tags"},
                       {"name": "remote", "description": "Manage remotes"}
                     ]
                   }
                 }
                """;
        try (TestWebServer ignore = new TestWebServer(9876, mcpToolsHandler(mcpToolsListResponse))) {
            // "git" toolset has allowedTools: ["branch", "remote"]
            Response resp = send(HttpMethod.GET, "/v1/toolset/git/allowed-tools");

            assertEquals(200, resp.status());
            var json = ProxyUtil.MAPPER.readTree(resp.body());
            ArrayNode tools = Optional.ofNullable(json.get("tools"))
                    .map(node -> (ArrayNode) node)
                    .orElse(ProxyUtil.MAPPER.createArrayNode());
            assertEquals(2, tools.size());
            assertEquals("branch", tools.get(0).get("name").asText());
            assertEquals("remote", tools.get(1).get("name").asText());
        }
    }

    @SuppressWarnings("checkstyle:LineLength")
    @Test
    void testGetAllowedTools_SseResponse() throws JsonProcessingException {
        String sseResponse = "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":["
                + "{\"name\":\"branch\",\"description\":\"Manage branches\"},"
                + "{\"name\":\"tag\",\"description\":\"Manage tags\"},"
                + "{\"name\":\"remote\",\"description\":\"Manage remotes\"}]}}\n\n";
        try (TestWebServer ignore = new TestWebServer(9876, mcpToolsHandler(sseResponse, "text/event-stream"))) {
            // "git" toolset has allowedTools: ["branch", "remote"]
            Response resp = send(HttpMethod.GET, "/v1/toolset/git/allowed-tools");

            assertEquals(200, resp.status());
            var json = ProxyUtil.MAPPER.readTree(resp.body());
            ArrayNode tools = Optional.ofNullable(json.get("tools"))
                    .map(node -> (ArrayNode) node)
                    .orElse(ProxyUtil.MAPPER.createArrayNode());
            assertEquals(2, tools.size());
            assertEquals("branch", tools.get(0).get("name").asText());
            assertEquals("remote", tools.get(1).get("name").asText());
        }
    }

    @DialConfigLocation("dial-config/filter-toolsets.json")
    @Test
    void testGetAllowedTools_EmptyFilter() throws JsonProcessingException {
        String mcpToolsListResponse = """
                {
                   "jsonrpc": "2.0",
                   "id": 2,
                   "result": {
                     "tools": [
                       {"name": "tool1", "description": "Tool 1"},
                       {"name": "tool2", "description": "Tool 2"}
                     ]
                   }
                 }
                """;
        try (TestWebServer ignore = new TestWebServer(9876, mcpToolsHandler(mcpToolsListResponse))) {
            // "my toolset 1" has no allowedTools filter (empty list)
            Response resp = send(HttpMethod.GET, "/v1/toolset/git/allowed-tools");

            assertEquals(200, resp.status());
            var json = ProxyUtil.MAPPER.readTree(resp.body());
            ArrayNode tools = Optional.ofNullable(json.get("tools"))
                    .map(node -> (ArrayNode) node)
                    .orElse(ProxyUtil.MAPPER.createArrayNode());
            assertEquals(2, tools.size());
        }
    }

    @Test
    void testGetAllTools_NotFound() {
        Response resp = send(HttpMethod.GET, "/v1/toolset/unknown-toolset/tools",
                null, null, "authorization", "admin");
        assertEquals(404, resp.status());
    }

    @Test
    void testGetAllTools_ResourceToolset_OwnerAccess() throws JsonProcessingException {
        // create resource-based toolset (owner = default user)
        Response response = send(HttpMethod.PUT,
                "/v1/toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-tools-test", null, """
                {
                    "endpoint": "http://localhost:9876",
                    "transport": "HTTP",
                    "allowedTools": ["tool1"]
                }
                """);
        verify(response, 200);

        String mcpToolsListResponse = """
                {
                   "jsonrpc": "2.0",
                   "id": 2,
                   "result": {
                     "tools": [
                       {"name": "tool1", "description": "Tool 1"},
                       {"name": "tool2", "description": "Tool 2"}
                     ]
                   }
                 }
                """;
        try (TestWebServer ignore = new TestWebServer(9876, mcpToolsHandler(mcpToolsListResponse))) {
            // owner should have WRITE access and can access /tools (unfiltered)
            Response resp = send(HttpMethod.GET,
                    "/v1/toolset/toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-tools-test/tools");

            assertEquals(200, resp.status());
            var json = ProxyUtil.MAPPER.readTree(resp.body());
            ArrayNode tools = Optional.ofNullable(json.get("tools"))
                    .map(node -> (ArrayNode) node)
                    .orElse(ProxyUtil.MAPPER.createArrayNode());
            assertEquals(2, tools.size());

            // /allowed-tools should filter
            resp = send(HttpMethod.GET,
                    "/v1/toolset/toolsets/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-tools-test/allowed-tools");

            assertEquals(200, resp.status());
            json = ProxyUtil.MAPPER.readTree(resp.body());
            tools = Optional.ofNullable(json.get("tools"))
                    .map(node -> (ArrayNode) node)
                    .orElse(ProxyUtil.MAPPER.createArrayNode());
            assertEquals(1, tools.size());
            assertEquals("tool1", tools.get(0).get("name").asText());
        }
    }

    @Test
    void testGetAllTools_Pagination() throws JsonProcessingException {
        String page1Response = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "result": {
                    "tools": [
                      {"name": "branch", "description": "Manage branches"},
                      {"name": "tag", "description": "Manage tags"}
                    ],
                    "nextCursor": "page2"
                  }
                }
                """;
        String page2Response = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "result": {
                    "tools": [
                      {"name": "remote", "description": "Manage remotes"}
                    ]
                  }
                }
                """;
        try (TestWebServer ignore = new TestWebServer(9876, mcpPaginatedToolsHandler(page1Response, page2Response))) {
            Response resp = send(HttpMethod.GET, "/v1/toolset/git/tools", null, null, "authorization", "admin");

            assertEquals(200, resp.status());
            var json = ProxyUtil.MAPPER.readTree(resp.body());
            ArrayNode tools = Optional.ofNullable(json.get("tools"))
                    .map(node -> (ArrayNode) node)
                    .orElse(ProxyUtil.MAPPER.createArrayNode());
            assertEquals(2, tools.size());
            assertEquals("branch", tools.get(0).get("name").asText());
            assertEquals("tag", tools.get(1).get("name").asText());
            assertEquals("page2", json.get("nextCursor").asText());

            resp = send(HttpMethod.GET, "/v1/toolset/git/tools?nextCursor=page2",
                    null, null, "authorization", "admin");

            assertEquals(200, resp.status());
            json = ProxyUtil.MAPPER.readTree(resp.body());
            tools = Optional.ofNullable(json.get("tools"))
                    .map(node -> (ArrayNode) node)
                    .orElse(ProxyUtil.MAPPER.createArrayNode());
            assertEquals(1, tools.size());
            assertEquals("remote", tools.get(0).get("name").asText());
            assertTrue(json.get("nextCursor") == null || json.get("nextCursor").isNull());
        }
    }

    @Test
    void testGetAllowedTools_AutoPagination() throws JsonProcessingException {
        // git toolset has allowedTools: ["branch", "remote"]; page1 has branch+tag, page2 has remote
        String page1Response = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "result": {
                    "tools": [
                      {"name": "branch", "description": "Manage branches"},
                      {"name": "tag", "description": "Manage tags"}
                    ],
                    "nextCursor": "page2"
                  }
                }
                """;
        String page2Response = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "result": {
                    "tools": [
                      {"name": "remote", "description": "Manage remotes"}
                    ]
                  }
                }
                """;
        try (TestWebServer ignore = new TestWebServer(9876, mcpPaginatedToolsHandler(page1Response, page2Response))) {
            Response resp = send(HttpMethod.GET, "/v1/toolset/git/allowed-tools");

            assertEquals(200, resp.status());
            var json = ProxyUtil.MAPPER.readTree(resp.body());
            ArrayNode tools = Optional.ofNullable(json.get("tools"))
                    .map(node -> (ArrayNode) node)
                    .orElse(ProxyUtil.MAPPER.createArrayNode());
            // "tag" filtered out, "branch" and "remote" (from page 2) included
            assertEquals(2, tools.size());
            assertEquals("branch", tools.get(0).get("name").asText());
            assertEquals("remote", tools.get(1).get("name").asText());
            assertTrue(json.get("nextCursor") == null || json.get("nextCursor").isNull());
        }
    }

    private static String extractCursor(String body) {
        try {
            JsonNode node = ProxyUtil.MAPPER.readTree(body);
            JsonNode cursor = node.path("params").path("cursor");
            return cursor.isMissingNode() || cursor.isNull() ? null : cursor.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static TestWebServer.Handler mcpPaginatedToolsHandler(
            String firstPageResponse, String subsequentPageResponse) {
        return request -> {
            String body = request.getBody().readString(StandardCharsets.UTF_8);
            if ("GET".equals(request.getMethod())) {
                return new MockResponse().setResponseCode(405);
            }
            if (body.contains("\"method\":\"initialize\"") || body.contains("\"method\": \"initialize\"")) {
                String id = extractJsonRpcId(body);
                String initResponse = """
                        {"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"test-server","version":"1.0"}}}
                        """.formatted(id);
                return new MockResponse().setBody(initResponse).setHeader("Content-Type", "application/json");
            } else if (body.contains("\"method\":\"notifications/initialized\"")
                    || body.contains("\"method\": \"notifications/initialized\"")) {
                return new MockResponse().setResponseCode(202).setHeader("Content-Type", "application/json");
            } else if (body.contains("\"method\":\"tools/list\"") || body.contains("\"method\": \"tools/list\"")) {
                String cursor = extractCursor(body);
                String id = extractJsonRpcId(body);
                String template = cursor == null ? firstPageResponse : subsequentPageResponse;
                String response = template.replaceFirst("\"id\"\\s*:\\s*\\d+", "\"id\":" + id);
                return new MockResponse().setBody(response).setHeader("Content-Type", "application/json");
            }
            return new MockResponse().setResponseCode(400).setBody("Unknown method");
        };
    }

    @Test
    void testOauthTokenRefresh_Success() {
        // Token endpoint: returns expired token on sign-in, fresh token on refresh
        String signInTokenResponse = """
                {
                    "access_token": "expired-access-token",
                    "refresh_token": "test-refresh-token",
                    "expires_in": 0
                }
                """;
        String refreshTokenResponse = """
                {
                    "access_token": "refreshed-access-token",
                    "refresh_token": "new-refresh-token",
                    "expires_in": 3600
                }
                """;

        String mcpResponse = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "result": {
                    "content": [{ "type": "text", "text": "ok" }]
                  }
                }
                """;

        try (TestWebServer server = new TestWebServer(9876)) {
            // Token endpoint: differentiate sign-in vs refresh by grant_type
            server.map(HttpMethod.POST, "/token", request -> {
                String body = request.getBody().readUtf8();
                if (body.contains("grant_type=refresh_token")) {
                    return new MockResponse()
                            .setBody(refreshTokenResponse)
                            .setHeader("Content-Type", "application/json");
                }
                // sign-in (authorization_code grant)
                return new MockResponse()
                        .setBody(signInTokenResponse)
                        .setHeader("Content-Type", "application/json");
            });

            // MCP endpoint: verify the refreshed token is used
            server.map(HttpMethod.POST, "/", request -> {
                String auth = request.getHeader("Authorization");
                assertEquals("Bearer refreshed-access-token", auth);
                return new MockResponse()
                        .setBody(mcpResponse)
                        .setHeader("Content-Type", "application/json");
            });

            // Sign in with OAuth (token expires immediately due to expires_in: 0)
            Response response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "oauth-toolset",
                        "credentialsLevel": "GLOBAL",
                        "authenticationType": "OAUTH",
                        "code": "auth-code"
                    }
                    """, "authorization", "admin");
            verify(response, 200, "true");

            // MCP call should trigger token refresh and use the refreshed token
            ApiKeyData apiKey = createAdminAppKey();
            apiKeyStore.assignPerRequestApiKey(apiKey);

            Response resp = send(HttpMethod.POST, "/v1/toolset/oauth-toolset/mcp", null,
                    MCP_TOOL_CALL_REQUEST, "Content-Type", "application/json", "api-key", apiKey.getPerRequestKey());

            assertEquals(200, resp.status());
            assertEquals(mcpResponse, resp.body());
        }
    }

    @Test
    void testOauthTokenRefresh_InvalidGrant_RemovesCredentials() {
        // Token endpoint: returns expired token on sign-in
        String signInTokenResponse = """
                {
                    "access_token": "expired-access-token",
                    "refresh_token": "revoked-refresh-token",
                    "expires_in": 0
                }
                """;

        String invalidGrantResponse = """
                {
                    "error": "invalid_grant",
                    "error_description": "Token has been expired or revoked."
                }
                """;

        try (TestWebServer server = new TestWebServer(9876)) {
            // Token endpoint
            server.map(HttpMethod.POST, "/token", request -> {
                String body = request.getBody().readUtf8();
                if (body.contains("grant_type=refresh_token")) {
                    // Refresh fails with invalid_grant
                    return new MockResponse()
                            .setResponseCode(400)
                            .setBody(invalidGrantResponse)
                            .setHeader("Content-Type", "application/json");
                }
                // sign-in succeeds
                return new MockResponse()
                        .setBody(signInTokenResponse)
                        .setHeader("Content-Type", "application/json");
            });

            // MCP endpoint (will receive request without auth after credential failure)
            server.map(HttpMethod.POST, "/", request ->
                    new MockResponse()
                            .setBody(MCP_TOOL_CALL_RESPONSE)
                            .setHeader("Content-Type", "application/json"));

            // Sign in with OAuth
            Response response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "oauth-toolset",
                        "credentialsLevel": "GLOBAL",
                        "authenticationType": "OAUTH",
                        "code": "auth-code"
                    }
                    """, "authorization", "admin");
            verify(response, 200, "true");

            // Verify auth status is SIGNED_IN after sign-in
            response = send(HttpMethod.GET, "/openai/toolsets/oauth-toolset", null, null, "authorization", "admin");
            assertEquals(200, response.status());
            assertTrue(response.body().contains("\"global_auth_status\":\"SIGNED_IN\""),
                    "Expected SIGNED_IN after sign-in but got: " + response.body());

            // MCP call triggers refresh → invalid_grant → credentials removed
            ApiKeyData apiKey = createAdminAppKey();
            apiKeyStore.assignPerRequestApiKey(apiKey);

            Response mcpResponse = send(HttpMethod.POST, "/v1/toolset/oauth-toolset/mcp", null,
                    MCP_TOOL_CALL_REQUEST, "Content-Type", "application/json", "api-key", apiKey.getPerRequestKey());
            assertEquals(401, mcpResponse.status(),
                    "Expected 401 (re-auth needed) when refresh fails permanently, got: " + mcpResponse.status());

            // Verify credentials were removed (auth status back to SIGNED_OUT)
            response = send(HttpMethod.GET, "/openai/toolsets/oauth-toolset", null, null, "authorization", "admin");
            assertEquals(200, response.status());
            assertTrue(response.body().contains("\"global_auth_status\":\"SIGNED_OUT\""),
                    "Expected SIGNED_OUT after invalid_grant but got: " + response.body());
        }
    }

    @Test
    void testOauthTokenRefresh_Unauthorized_RemovesCredentials() {
        // Token endpoint: returns expired token on sign-in
        String signInTokenResponse = """
                {
                    "access_token": "expired-access-token",
                    "refresh_token": "revoked-refresh-token",
                    "expires_in": 0
                }
                """;

        // Notion-style 401 from /token with non-spec invalid_token error body
        String invalidTokenResponse = """
                {
                    "error": "invalid_token",
                    "error_description": "Missing or invalid access token"
                }
                """;

        try (TestWebServer server = new TestWebServer(9876)) {
            // Token endpoint
            server.map(HttpMethod.POST, "/token", request -> {
                String body = request.getBody().readUtf8();
                if (body.contains("grant_type=refresh_token")) {
                    // Refresh fails with 401 (Notion behavior on expired refresh token)
                    return new MockResponse()
                            .setResponseCode(401)
                            .setBody(invalidTokenResponse)
                            .setHeader("Content-Type", "application/json");
                }
                // sign-in succeeds
                return new MockResponse()
                        .setBody(signInTokenResponse)
                        .setHeader("Content-Type", "application/json");
            });

            // MCP endpoint (will receive request without auth after credential failure)
            server.map(HttpMethod.POST, "/", request ->
                    new MockResponse()
                            .setBody(MCP_TOOL_CALL_RESPONSE)
                            .setHeader("Content-Type", "application/json"));

            // Sign in with OAuth
            Response response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "oauth-toolset",
                        "credentialsLevel": "GLOBAL",
                        "authenticationType": "OAUTH",
                        "code": "auth-code"
                    }
                    """, "authorization", "admin");
            verify(response, 200, "true");

            // Verify auth status is SIGNED_IN after sign-in
            response = send(HttpMethod.GET, "/openai/toolsets/oauth-toolset", null, null, "authorization", "admin");
            assertEquals(200, response.status());
            assertTrue(response.body().contains("\"global_auth_status\":\"SIGNED_IN\""),
                    "Expected SIGNED_IN after sign-in but got: " + response.body());

            // MCP call triggers refresh → 401 → credentials removed
            ApiKeyData apiKey = createAdminAppKey();
            apiKeyStore.assignPerRequestApiKey(apiKey);

            Response mcpResponse = send(HttpMethod.POST, "/v1/toolset/oauth-toolset/mcp", null,
                    MCP_TOOL_CALL_REQUEST, "Content-Type", "application/json", "api-key", apiKey.getPerRequestKey());
            assertEquals(401, mcpResponse.status(),
                    "Expected 401 (re-auth needed) when refresh fails permanently, got: " + mcpResponse.status());

            // Verify credentials were removed (auth status back to SIGNED_OUT)
            response = send(HttpMethod.GET, "/openai/toolsets/oauth-toolset", null, null, "authorization", "admin");
            assertEquals(200, response.status());
            assertTrue(response.body().contains("\"global_auth_status\":\"SIGNED_OUT\""),
                    "Expected SIGNED_OUT after 401 refresh failure but got: " + response.body());
        }
    }

    @Test
    void testOauthTokenRefresh_BadRequest_RemovesCredentials() {
        // Token endpoint: returns expired token on sign-in
        String signInTokenResponse = """
                {
                    "access_token": "expired-access-token",
                    "refresh_token": "revoked-refresh-token",
                    "expires_in": 0
                }
                """;

        // Generic 400 without invalid_grant (e.g., provider-specific error code)
        String genericBadRequestResponse = """
                {
                    "error": "unauthorized_client",
                    "error_description": "Client is not authorized to use refresh token grant"
                }
                """;

        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/token", request -> {
                String body = request.getBody().readUtf8();
                if (body.contains("grant_type=refresh_token")) {
                    return new MockResponse()
                            .setResponseCode(400)
                            .setBody(genericBadRequestResponse)
                            .setHeader("Content-Type", "application/json");
                }
                return new MockResponse()
                        .setBody(signInTokenResponse)
                        .setHeader("Content-Type", "application/json");
            });

            server.map(HttpMethod.POST, "/", request ->
                    new MockResponse()
                            .setBody(MCP_TOOL_CALL_RESPONSE)
                            .setHeader("Content-Type", "application/json"));

            Response response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "oauth-toolset",
                        "credentialsLevel": "GLOBAL",
                        "authenticationType": "OAUTH",
                        "code": "auth-code"
                    }
                    """, "authorization", "admin");
            verify(response, 200, "true");

            ApiKeyData apiKey = createAdminAppKey();
            apiKeyStore.assignPerRequestApiKey(apiKey);

            Response mcpResponse = send(HttpMethod.POST, "/v1/toolset/oauth-toolset/mcp", null,
                    MCP_TOOL_CALL_REQUEST, "Content-Type", "application/json", "api-key", apiKey.getPerRequestKey());
            assertEquals(401, mcpResponse.status(),
                    "Expected 401 (re-auth needed) when refresh fails permanently, got: " + mcpResponse.status());

            response = send(HttpMethod.GET, "/openai/toolsets/oauth-toolset", null, null, "authorization", "admin");
            assertEquals(200, response.status());
            assertTrue(response.body().contains("\"global_auth_status\":\"SIGNED_OUT\""),
                    "Expected SIGNED_OUT after 400 refresh failure but got: " + response.body());
        }
    }

    @Test
    void testOauthTokenRefresh_TwoHundredWithErrorBody_RemovesCredentials() {
        // Token endpoint: returns expired token on sign-in
        String signInTokenResponse = """
                {
                    "access_token": "expired-access-token",
                    "refresh_token": "revoked-refresh-token",
                    "expires_in": 0
                }
                """;

        // Non-spec: some OAuth servers return HTTP 200 with an error payload instead of 4xx
        String twoHundredErrorResponse = """
                {
                    "error": "invalid_grant",
                    "error_description": "Token has been expired or revoked."
                }
                """;

        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/token", request -> {
                String body = request.getBody().readUtf8();
                if (body.contains("grant_type=refresh_token")) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setBody(twoHundredErrorResponse)
                            .setHeader("Content-Type", "application/json");
                }
                return new MockResponse()
                        .setBody(signInTokenResponse)
                        .setHeader("Content-Type", "application/json");
            });

            server.map(HttpMethod.POST, "/", request ->
                    new MockResponse()
                            .setBody(MCP_TOOL_CALL_RESPONSE)
                            .setHeader("Content-Type", "application/json"));

            Response response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "oauth-toolset",
                        "credentialsLevel": "GLOBAL",
                        "authenticationType": "OAUTH",
                        "code": "auth-code"
                    }
                    """, "authorization", "admin");
            verify(response, 200, "true");

            ApiKeyData apiKey = createAdminAppKey();
            apiKeyStore.assignPerRequestApiKey(apiKey);

            Response mcpResponse = send(HttpMethod.POST, "/v1/toolset/oauth-toolset/mcp", null,
                    MCP_TOOL_CALL_REQUEST, "Content-Type", "application/json", "api-key", apiKey.getPerRequestKey());
            assertEquals(401, mcpResponse.status(),
                    "Expected 401 (re-auth needed) when refresh fails permanently, got: " + mcpResponse.status());

            response = send(HttpMethod.GET, "/openai/toolsets/oauth-toolset", null, null, "authorization", "admin");
            assertEquals(200, response.status());
            assertTrue(response.body().contains("\"global_auth_status\":\"SIGNED_OUT\""),
                    "Expected SIGNED_OUT after 200-with-error-body refresh but got: " + response.body());
        }
    }

    @Test
    void testOauthTokenRefresh_ServerError_KeepsCredentials() {
        // Token endpoint: returns expired token on sign-in
        String signInTokenResponse = """
                {
                    "access_token": "expired-access-token",
                    "refresh_token": "test-refresh-token",
                    "expires_in": 0
                }
                """;

        try (TestWebServer server = new TestWebServer(9876)) {
            // Token endpoint
            server.map(HttpMethod.POST, "/token", request -> {
                String body = request.getBody().readUtf8();
                if (body.contains("grant_type=refresh_token")) {
                    // Refresh fails with server error (not invalid_grant)
                    return new MockResponse()
                            .setResponseCode(500)
                            .setBody("Internal Server Error")
                            .setHeader("Content-Type", "text/plain");
                }
                // sign-in succeeds
                return new MockResponse()
                        .setBody(signInTokenResponse)
                        .setHeader("Content-Type", "application/json");
            });

            // MCP endpoint
            server.map(HttpMethod.POST, "/", request ->
                    new MockResponse()
                            .setBody(MCP_TOOL_CALL_RESPONSE)
                            .setHeader("Content-Type", "application/json"));

            // Sign in with OAuth
            Response response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "oauth-toolset",
                        "credentialsLevel": "GLOBAL",
                        "authenticationType": "OAUTH",
                        "code": "auth-code"
                    }
                    """, "authorization", "admin");
            verify(response, 200, "true");

            // MCP call triggers refresh → server error → credentials kept
            ApiKeyData apiKey = createAdminAppKey();
            apiKeyStore.assignPerRequestApiKey(apiKey);

            send(HttpMethod.POST, "/v1/toolset/oauth-toolset/mcp", null,
                    MCP_TOOL_CALL_REQUEST, "Content-Type", "application/json", "api-key", apiKey.getPerRequestKey());

            // Verify credentials are still present (auth status still SIGNED_IN)
            response = send(HttpMethod.GET, "/openai/toolsets/oauth-toolset", null, null, "authorization", "admin");
            assertEquals(200, response.status());
            assertTrue(response.body().contains("\"global_auth_status\":\"SIGNED_IN\""),
                    "Expected SIGNED_IN after server error but got: " + response.body());
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

    @Test
    void testToolsetEndpointHiddenForReadOnlyUser() throws JsonProcessingException {
        // Admin creates toolset with endpoint
        Response response = send(HttpMethod.PUT, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/secure-toolset", null, """
                {
                    "endpoint": "http://localhost:9876",
                    "transport": "HTTP",
                    "allowedTools": [],
                    "auth_settings": {
                        "authentication_type": "NONE"
                    }
                }
                """, "authorization", "admin");
        verify(response, 200);

        // Admin GETs the toolset - should see endpoint
        response = send(HttpMethod.GET, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/secure-toolset", null, null, "authorization", "admin");
        verify(response, 200);
        assertNotNull(ProxyUtil.MAPPER.readTree(response.body()).get("endpoint"));

        // Admin shares toolset with user (read-only)
        response = send(HttpMethod.POST, "/v1/ops/resource/share/create", null, """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "toolsets/4X25dj1mja51jykqxsXnCH/secure-toolset"
                    }
                  ]
                }
                """, "authorization", "admin");
        verify(response, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(response.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        // User accepts invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "authorization", "user");
        verify(response, 200);

        // User GETs the toolset - endpoint must be absent
        response = send(HttpMethod.GET, "/v1/toolsets/4X25dj1mja51jykqxsXnCH/secure-toolset", null, null, "authorization", "user");
        verify(response, 200);
        assertNull(ProxyUtil.MAPPER.readTree(response.body()).get("endpoint"));
    }
}
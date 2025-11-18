package com.epam.aidial.core.server;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.data.InvitationLink;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
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
                        "authentication_type" : "NONE"
                     }
                   } ],
                   "object" : "list"
                 }
                """);

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
                        "authentication_type" : "NONE"
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

}

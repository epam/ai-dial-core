package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

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
                   "endpoint" : "http://toolset/v1/mcp",
                   "display_name" : "My Toolset",
                   "display_version" : "1.0",
                   "icon_url" : "http://toolset/icon.svg",
                   "description" : "My toolset Description",
                   "transport" : "HTTP",
                   "allowedTools" : [ "tool1", "tool2" ]
                 }
                """);
    }

    @Test
    void testToolSetListing() {

        Response response = send(HttpMethod.GET, "/openai/toolsets");
        verifyJsonNotExact(response, 200, """
                {
                   "data" : [ {
                     "description" : "Git remote tool set",
                     "owner" : "organization-owner",
                     "object" : "application",
                     "status" : "succeeded",
                     "created_at" : 1672534800,
                     "updated_at" : 1672534800,
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

        response = send(HttpMethod.GET, "/openai/toolsets");
        verifyJsonNotExact(response, 200, """
                {
                    "data" : [ {
                      "description" : "Git remote tool set",
                      "owner" : "organization-owner",
                      "object" : "application",
                      "status" : "succeeded",
                      "created_at" : 1672534800,
                      "updated_at" : 1672534800,
                      "description_keywords" : [ ],
                      "max_retry_attempts" : 1,
                      "transport" : "HTTP",
                      "allowed_tools" : [ "branch", "remote" ]
                    }, {
                      "display_name" : "My Toolset",
                      "display_version" : "1.0",
                      "icon_url" : "http://toolset/icon.svg",
                      "description" : "My toolset Description",
                      "owner" : "EPM-RTC-GPT",
                      "object" : "application",
                      "status" : "succeeded",
                      "created_at" : "@ignore",
                      "updated_at" : "@ignore",
                      "description_keywords" : [ ],
                      "max_retry_attempts" : 1,
                      "transport" : "HTTP",
                      "allowed_tools" : [ "tool1", "tool2" ]
                    } ],
                    "object" : "list"
                  }
                """);

    }
}

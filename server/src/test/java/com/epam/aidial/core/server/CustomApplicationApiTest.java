package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.data.InvitationLink;
import com.epam.aidial.core.server.util.JsonUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import okhttp3.mockwebserver.MockResponse;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class CustomApplicationApiTest extends ResourceBaseTest {

    @Test
    void testApplicationCreation() {
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, """
                {
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description"
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

        response = send(HttpMethod.GET, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, "");
        verifyJsonNotExact(response, 200, """
                {
                "name":"applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                "endpoint":"http://application1/v1/completions",
                "display_name":"My Custom Application",
                "display_version":"1.0",
                "icon_url":"http://application1/icon.svg",
                "description":"My Custom Application Description",
                "reference": "@ignore",
                "forward_auth_token":false,
                "defaults": {},
                "responses_defaults": {},
                "interceptors": [],
                "description_keywords": [],
                "max_retry_attempts" : 1,
                "dependencies" : [ ],
                "author" : "EPM-RTC-GPT",
                "created_at" : "@ignore",
                "updated_at" : "@ignore",
                "routes" : { }
                }
                """);

        response = send(HttpMethod.GET, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, "", "Api-key", "proxyKey2");
        verify(response, 403);

        // verify custom app creation fails if resource already present
        response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, """
                {
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description"
                }
                """, "If-None-Match", "*");
        verify(response, 412);

        // verify able to create new app with provided reference
        response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application2", null, """
                {
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description",
                "reference": "ref1"
                }
                """, "If-None-Match", "*");
        verify(response, 200);

        response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/unknown-app-schema-case", null, """
                {
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "applicationTypeSchemaId": "http://unknown/schema/id",
                "description": "My Custom Application Description"
                }
                """);
        verify(response, 400);

        // verify custom app creation fails if dependency is unknown
        response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, """
                {
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description",
                "dependencies": ["unknown-app"]
                }
                """);
        verify(response, 400);

        response = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "admin");
        String adminBucket = new JsonObject(response.body()).getString("bucket");

        // verify custom app creation fails if interceptor is unknown
        response = send(HttpMethod.PUT, "/v1/applications/%s/my-custom-application".formatted(adminBucket), null, """
                {
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description",
                "interceptors": ["unknown-interceptor"]
                }
                """, "authorization", "admin");
        verify(response, 400);

        response = send(HttpMethod.PUT, "/v1/applications/%s/my-custom-application".formatted(adminBucket), null, """
                {
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description",
                "interceptors": ["interceptor1"]
                }
                """, "authorization", "admin");
        verify(response, 200);

    }

    @Test
    void testPutApplicationWithEmptyPayload() {
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, "");
        verify(response, 400);
    }

    @Test
    void testApplicationListing() {
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, """
                {
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description",
                "features": {
                 "rate_endpoint": "http://application1/rate",
                 "configuration_endpoint": "http://application1/configuration"
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

        response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/gpt/my-custom-application", null, """
                {
                "endpoint": "http://application2/v1/completions",
                "display_name": "My Custom Application 2",
                "display_version": "1.1",
                "icon_url": "http://application2/icon.svg",
                "description": "My Custom Application 2 Description"
                }
                """);
        verifyJsonNotExact(response, 200, """
                {
                "name":"my-custom-application",
                "parentPath": "gpt",
                "bucket":"3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                "url":"applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/gpt/my-custom-application",
                "nodeType":"ITEM",
                "resourceType":"APPLICATION",
                "createdAt": "@ignore",
                "updatedAt":"@ignore",
                "etag":"@ignore",
                "author" : "EPM-RTC-GPT"
                }
                """);

        response = send(HttpMethod.GET, "/v1/metadata/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/", null, "");
        verifyJsonNotExact(response, 200, """
                {
                "name":null,
                "parentPath":null,
                "bucket":"3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                "url":"applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/",
                "nodeType":"FOLDER",
                "resourceType":"APPLICATION",
                "items":[
                 {
                 "name":"gpt",
                 "parentPath":null,
                 "bucket":"3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                 "url":"applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/gpt/",
                 "nodeType":"FOLDER",
                 "resourceType":"APPLICATION",
                 "items":null
                 },
                 {
                 "name":"my-custom-application",
                 "parentPath":null,
                 "bucket":"3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                 "url":"applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                 "nodeType":"ITEM",
                 "resourceType":"APPLICATION",
                 "updatedAt":"@ignore"
                 }]
                }
                """);
    }

    @Test
    void testApplicationDeletion() {
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, """
                {
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description",
                "features": {
                 "rate_endpoint": "http://application1/rate",
                 "configuration_endpoint": "http://application1/configuration"
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

        response = send(HttpMethod.GET, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, "");
        verifyJsonNotExact(response, 200, """
                {
                "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description",
                "reference": "@ignore",
                "forward_auth_token": false,
                "features": {
                 "rate_endpoint": "http://application1/rate",
                 "configuration_endpoint": "http://application1/configuration"
                 },
                "defaults": {},
                "responses_defaults": {},
                "interceptors": [],
                "description_keywords":[],
                "max_retry_attempts" : 1,
                "dependencies" : [ ],
                "author" : "EPM-RTC-GPT",
                "created_at" : "@ignore",
                "updated_at" : "@ignore",
                "routes" : { }
                }
                """);

        response = send(HttpMethod.DELETE, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, "");
        verify(response, 200);

        response = send(HttpMethod.GET, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, "");
        verify(response, 404);
    }

    @Test
    void testApplicationSharing() {
        // check no applications shared with me
        Response response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["APPLICATION"],
                  "with": "me"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources": []
                }
                """);

        // check no applications shared by me
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["APPLICATION"],
                  "with": "others"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources": []
                }
                """);

        // create application
        response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, """
                {
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description",
                "features": {
                 "rate_endpoint": "http://application1/rate",
                 "configuration_endpoint": "http://application1/configuration"
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

        // initialize share request
        response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application"
                    }
                  ]
                }
                """);
        verify(response, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(response.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        // verify invitation details
        response = send(HttpMethod.GET, invitationLink.invitationLink(), null, null);
        verifyNotExact(response, 200, "\"url\":\"applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application\"");

        // verify user2 do not have access to the application
        response = send(HttpMethod.GET, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, "", "Api-key", "proxyKey2");
        verify(response, 403);

        // accept invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2");
        verify(response, 200);

        // verify user2 has access to the application
        response = send(HttpMethod.GET, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, "", "Api-key", "proxyKey2");
        verifyJsonNotExact(response, 200, """
                {
                   "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                   "display_name" : "My Custom Application",
                   "display_version" : "1.0",
                   "icon_url" : "http://application1/icon.svg",
                   "description" : "My Custom Application Description",
                   "reference" : "@ignore",
                   "forward_auth_token" : false,
                   "features" : { },
                   "defaults" : { },
                   "responses_defaults" : { },
                   "interceptors" : [ ],
                   "description_keywords" : [ ],
                   "max_retry_attempts" : 1,
                   "author" : "EPM-RTC-GPT",
                   "created_at" : "@ignore",
                   "updated_at" : "@ignore",
                   "dependencies" : [ ],
                   "routes" : { }
                 }
                """);
    }

    @Test
    void testApplicationPublication() {
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, """
                {
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description",
                "features": {
                 "rate_endpoint": "http://application1/rate",
                 "configuration_endpoint": "http://application1/configuration"
                 }
                }
                """);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", """
                {
                  "targetFolder": "public/folder/",
                  "displayAuthor": "dream-team",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "applications/%s/my-custom-application",
                      "targetUrl": "applications/public/folder/my-custom-application"
                    }
                  ],
                  "rules": [
                    {
                      "source": "roles",
                      "function": "EQUAL",
                      "targets": ["user"]
                    }
                  ]
                }
                """.formatted(bucket));
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/approve", """
                {
                "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123"
                }
                """, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "targetFolder" : "public/folder/",
                  "status" : "APPROVED",
                  "createdAt" : 0,
                  "resources" : [ {
                    "action": "ADD",
                    "sourceUrl" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                    "targetUrl" : "applications/public/folder/my-custom-application",
                    "reviewUrl" : "applications/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/my-custom-application",
                    "publishCredentials" : false
                   } ],
                   "resourceTypes" : [ "APPLICATION" ],
                   "rules" : [ {
                     "function" : "EQUAL",
                     "source" : "roles",
                     "targets" : [ "user" ]
                   } ],
                   "author" : "EPM-RTC-GPT",
                   "displayAuthor" : "dream-team"
                }
                """);

        response = send(HttpMethod.GET, "/v1/applications/public/folder/my-custom-application",
                null, null, "authorization", "admin");
        verify(response, 200);

        response = send(HttpMethod.GET, "/v1/applications/public/folder/my-custom-application",
                null, null, "authorization", "user");
        verify(response, 200);
    }

    @Test
    void testOpenAiApplicationListing() {
        // verify listing return only application from config
        Response response = send(HttpMethod.GET, "/openai/applications");
        verifyJson(response, 200, """
                {
                    "data":[
                        {
                            "id":"app",
                            "application":"app",
                            "display_name":"10k",
                            "icon_url":"http://localhost:7001/logo10k.png",
                            "description":"Some description of the application for testing",
                            "reference":"app",
                            "owner":"organization-owner",
                            "object":"application",
                            "status":"succeeded",
                            "created_at":1672534800,
                            "updated_at":1672534800,
                            "features":{
                                "rate":true,
                                "tokenize":false,
                                "truncate_prompt":false,
                                "configuration":true,
                                "system_prompt":false,
                                "tools":false,
                                "seed":false,
                                "url_attachments":false,
                                "folder_attachments":false,
                                "allow_resume": true,
                                "accessible_by_per_request_key": true,
                                "content_parts": false,
                                "temperature" : true,
                                "cache" : false,
                                "auto_caching" : false,
                                "parallel_tool_calls" : true,
                                "assistant_attachments_in_request": false,
                                "mcp" : false,
                                "chat_completion" : true,
                                "responses_api" : false,
                                "max_tokens_supported": true,
                                "max_completion_tokens_supported": false,
                                "custom_temperature_supported": true,
                                "reasoning_efforts": []
                                },
                            "defaults":{},
                            "responses_defaults":{},
                            "description_keywords":[],
                            "max_retry_attempts" : 1,
                            "routes" : { },
                            "viewer_url" : "http://some-host",
                             "editor_url" : "http://some-host"
                        }, {
                             "id" : "app-route",
                             "application" : "app-route",
                             "display_name" : "10k",
                             "icon_url" : "http://localhost:7001/logo10k.png",
                             "description" : "Some description of the application for testing",
                             "reference" : "app-route",
                             "owner" : "organization-owner",
                             "object" : "application",
                             "status" : "succeeded",
                             "created_at" : 1672534800,
                             "updated_at" : 1672534800,
                             "features" : {
                               "rate" : true,
                               "tokenize" : false,
                               "truncate_prompt" : false,
                               "configuration" : true,
                               "system_prompt" : false,
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
                               "mcp" : false,
                               "chat_completion" : true,
                               "responses_api" : false,
                                "max_tokens_supported": true,
                                "max_completion_tokens_supported": false,
                                "custom_temperature_supported": true,
                                "reasoning_efforts": []
                             },
                             "defaults" : { },
                             "responses_defaults" : { },
                             "description_keywords" : [ ],
                             "max_retry_attempts" : 1,
                             "routes" : {
                               "index-search" : {
                                 "rewritePath" : true,
                                 "paths" : [ "/v1/index(/[^/]+)*$" ],
                                 "methods" : [ "DELETE", "POST", "PUT" ],
                                 "maxRetryAttempts" : 1,
                                 "order" : 2147483647,
                                 "permissions" : [ ],
                                 "attachmentPaths" : {
                                   "requestBody" : [ "@.attachments[*].url" ],
                                   "responseBody" : [ "@.result.attachedFiles" ]
                                 }
                               }
                             }
                           }
                    ],
                    "object":"list"
                }
                """);

        // create custom application
        response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, """
                {
                    "endpoint": "http://application1/v1/completions",
                    "display_name": "My Custom Application",
                    "display_version": "1.0",
                    "icon_url": "http://application1/icon.svg",
                    "description": "My Custom Application Description",
                    "features": {
                        "rate_endpoint": "http://application1/rate",
                        "configuration_endpoint": "http://application1/configuration"
                    }
                }
                """);
        verify(response, 200);

        // get custom application with openai endpoint
        response = send(HttpMethod.GET, "/openai/applications/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application");
        verifyJsonNotExact(response, 200, """
                {
                    "id":"applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                    "application":"applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                    "display_name":"My Custom Application",
                    "display_version":"1.0",
                    "icon_url":"http://application1/icon.svg",
                    "description":"My Custom Application Description",
                    "reference":"@ignore",
                    "object":"application",
                    "status":"succeeded",
                    "features":{
                        "rate":true,
                        "tokenize":false,
                        "truncate_prompt":false,
                        "configuration":true,
                        "system_prompt":true,
                        "tools":false,
                        "seed":false,
                        "url_attachments":false,
                        "folder_attachments":false,
                        "allow_resume": true,
                        "accessible_by_per_request_key": true,
                        "content_parts": false,
                        "temperature" : true,
                        "cache" : false,
                        "auto_caching" : false,
                        "parallel_tool_calls" : true,
                        "assistant_attachments_in_request": false,
                        "mcp" : false,
                                "max_tokens_supported": true,
                                "max_completion_tokens_supported": false,
                                "custom_temperature_supported": true,
                                "reasoning_efforts": []
                    },
                    "defaults":{},
                    "responses_defaults":{},
                    "description_keywords":[],
                    "max_retry_attempts" : 1,
                    "owner" : "EPM-RTC-GPT",
                    "created_at" : "@ignore",
                    "updated_at" : "@ignore",
                    "routes" : { }
                }
                """);

        // verify listing returns both applications (from config and own)
        response = send(HttpMethod.GET, "/openai/applications");
        verifyJsonNotExact(response, 200, """
                {
                    "data":[
                        {
                            "id":"app",
                            "application":"app",
                            "display_name":"10k",
                            "icon_url":"http://localhost:7001/logo10k.png",
                            "description":"Some description of the application for testing",
                            "reference":"app",
                            "owner":"organization-owner",
                            "object":"application",
                            "status":"succeeded",
                            "created_at":1672534800,
                            "updated_at":1672534800,
                            "features":{
                                "rate":true,
                                "tokenize":false,
                                "truncate_prompt":false,
                                "configuration":true,
                                "system_prompt":false,
                                "tools":false,
                                "seed":false,
                                "url_attachments":false,
                                "folder_attachments":false,
                                "allow_resume": true,
                                "accessible_by_per_request_key": true,
                                "content_parts": false,
                                "temperature" : true,
                                "cache" : false,
                                "auto_caching" : false,
                                "parallel_tool_calls" : true,
                                "assistant_attachments_in_request": false,
                                "mcp" : false,
                                "max_tokens_supported": true,
                                "max_completion_tokens_supported": false,
                                "custom_temperature_supported": true,
                                "reasoning_efforts": []
                                },
                            "defaults":{},
                            "responses_defaults":{},
                            "description_keywords":[],
                            "max_retry_attempts" : 1,
                            "routes" : { },
                            "viewer_url" : "http://some-host",
                            "editor_url" : "http://some-host"
                        },
                        {
                            "id" : "app-route",
                            "application" : "app-route",
                            "display_name" : "10k",
                            "icon_url" : "http://localhost:7001/logo10k.png",
                            "description" : "Some description of the application for testing",
                            "reference" : "app-route",
                            "owner" : "organization-owner",
                            "object" : "application",
                            "status" : "succeeded",
                            "created_at" : 1672534800,
                            "updated_at" : 1672534800,
                            "features" : {
                              "rate" : true,
                              "tokenize" : false,
                              "truncate_prompt" : false,
                              "configuration" : true,
                              "system_prompt" : false,
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
                              "mcp" : false,
                                "max_tokens_supported": true,
                                "max_completion_tokens_supported": false,
                                "custom_temperature_supported": true,
                                "reasoning_efforts": []
                            },
                            "defaults" : { },
                            "responses_defaults" : { },
                            "description_keywords" : [ ],
                            "max_retry_attempts" : 1,
                            "routes" : {
                              "index-search" : {
                                "rewritePath" : true,
                                "paths" : [ "/v1/index(/[^/]+)*$" ],
                                "methods" : [ "DELETE", "POST", "PUT" ],
                                "maxRetryAttempts" : 1,
                                "order" : 2147483647,
                                "permissions" : [ ],
                                "attachmentPaths" : {
                                  "requestBody" : [ "@.attachments[*].url" ],
                                  "responseBody" : [ "@.result.attachedFiles" ]
                                }
                              }
                            }
                        },
                        {
                            "id" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                            "application" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                            "display_name" : "My Custom Application",
                            "display_version" : "1.0",
                            "icon_url" : "http://application1/icon.svg",
                            "description" : "My Custom Application Description",
                            "reference": "@ignore",
                            "object" : "application",
                            "status" : "succeeded",
                            "features" : {
                              "rate" : true,
                              "tokenize" : false,
                              "truncate_prompt" : false,
                              "configuration" : true,
                              "system_prompt" : true,
                              "tools" : false,
                              "seed" : false,
                              "url_attachments" : false,
                              "folder_attachments" : false,
                              "allow_resume": true,
                              "accessible_by_per_request_key": true,
                              "content_parts": false,
                              "temperature" : true,
                              "cache" : false,
                              "auto_caching" : false,
                              "parallel_tool_calls" : true,
                              "assistant_attachments_in_request": false,
                              "mcp" : false,
                                "max_tokens_supported": true,
                                "max_completion_tokens_supported": false,
                                "custom_temperature_supported": true,
                                "reasoning_efforts": []
                            },
                            "defaults" : { },
                            "responses_defaults" : { },
                            "description_keywords":[],
                            "max_retry_attempts" : 1,
                            "owner" : "EPM-RTC-GPT",
                            "created_at" : "@ignore",
                            "updated_at" : "@ignore",
                            "routes" : { }
                          }
                    ],
                    "object":"list"
                }
                """);
    }

    @Test
    void testMoveCustomApplication() {
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application1", null, """
                {
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description"
                }
                """);
        verifyJsonNotExact(response, 200, """
                {
                "name":"my-custom-application1",
                "parentPath":null,
                "bucket":"3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                "url":"applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application1",
                "nodeType":"ITEM",
                "resourceType":"APPLICATION",
                "createdAt": "@ignore",
                "updatedAt":"@ignore",
                "etag":"@ignore",
                "author" : "EPM-RTC-GPT"
                }
                """);

        response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application2", null, """
                {
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description"
                }
                """);
        verifyJsonNotExact(response, 200, """
                {
                "name":"my-custom-application2",
                "parentPath":null,
                "bucket":"3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                "url":"applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application2",
                "nodeType":"ITEM",
                "resourceType":"APPLICATION",
                "createdAt": "@ignore",
                "updatedAt":"@ignore",
                "etag":"@ignore",
                "author" : "EPM-RTC-GPT"
                }
                """);

        // verify app1
        response = send(HttpMethod.GET, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application1", null, "");
        verifyJsonNotExact(response, 200, """
                {
                "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application1",
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description",
                "reference": "@ignore",
                "forward_auth_token": false,
                "defaults": {},
                "responses_defaults": {},
                "interceptors": [],
                "description_keywords": [],
                "max_retry_attempts" : 1,
                "dependencies" : [ ],
                "author" : "EPM-RTC-GPT",
                "created_at" : "@ignore",
                "updated_at" : "@ignore",
                "routes" : { }
                }
                """);
        Application application1 = ProxyUtil.convertToObject(response.body(), Application.class);
        String reference1 = application1.getReference();

        // verify app2
        response = send(HttpMethod.GET, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application2", null, "");
        verifyJsonNotExact(response, 200, """
                {
                "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application2",
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description",
                "reference": "@ignore",
                "forward_auth_token": false,
                "defaults": {},
                "responses_defaults": {},
                "interceptors": [],
                "description_keywords": [],
                "max_retry_attempts" : 1,
                "dependencies" : [ ],
                "author" : "EPM-RTC-GPT",
                "created_at" : "@ignore",
                "updated_at" : "@ignore",
                "routes" : { }
                }
                """);
        Application application2 = ProxyUtil.convertToObject(response.body(), Application.class);
        String reference2 = application2.getReference();

        // verify references are not the same
        assertNotEquals(reference1, reference2);

        // verify move operation fails
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application1",
                   "destinationUrl": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application2"
                }
                """);
        verify(response, 412, "Resource already exists");

        // verify move operation succeed
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application1",
                   "destinationUrl": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application2",
                   "overwrite": true
                }
                """);
        verify(response, 200);

        // verify app2 after move operation
        response = send(HttpMethod.GET, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application2", null, "");
        verifyJsonNotExact(response, 200, """
                {
                "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application2",
                "endpoint": "http://application1/v1/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description",
                "reference": "@ignore",
                "forward_auth_token": false,
                "defaults": {},
                "responses_defaults": {},
                "interceptors": [],
                "description_keywords": [],
                "max_retry_attempts" : 1,
                "dependencies" : [ ],
                "author" : "EPM-RTC-GPT",
                "created_at" : "@ignore",
                "updated_at" : "@ignore",
                "routes" : { }
                }
                """);
        Application application = ProxyUtil.convertToObject(response.body(), Application.class);
        String reference = application.getReference();

        // verify app2 has same reference as app1
        assertEquals(reference1, reference);

        // verify app1 no longer exists
        response = send(HttpMethod.GET, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application1", null, "");
        verify(response, 404);
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

        response = send(HttpMethod.GET, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_files", null, "");
        verifyJsonNotExact(response, 200, """
                {
                  "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_files",
                  "display_name" : "test_app",
                  "icon_url" : "https://mydial.somewhere.com/app-icon.svg",
                  "description" : "My application description",
                  "reference": "@ignore",
                  "forward_auth_token" : false,
                  "defaults" : { },
                  "responses_defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "max_retry_attempts" : 1,
                  "dependencies" : [ ],
                  "author" : "EPM-RTC-GPT",
                  "created_at" : "@ignore",
                  "updated_at" : "@ignore",
                  "application_properties" : {
                    "property1" : "test property1",
                    "property2" : "test property2",
                    "property3" : [ "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_file1.txt", "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_file2.txt" ]
                  },
                  "application_type_schema_id" : "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                  "routes" : { }
                }
                """);
    }

    @Test
    void testApplicationWithTypeSchemaCreationAndThenGet_NoApplicationPropertiesGet_WhenNoApplicationPropertiesCreated() {

        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_files", null, """
                  {
                      "displayName": "test_app",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                       "userRoles": [
                            "Admin"
                       ],
                       "forwardAuthToken": true,
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """);
        Assertions.assertEquals(200, response.status());

        response = send(HttpMethod.GET, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_files", null, "");
        verifyJsonNotExact(response, 200, """
                {
                  "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_files",
                  "display_name" : "test_app",
                  "icon_url" : "https://mydial.somewhere.com/app-icon.svg",
                  "description" : "My application description",
                  "reference": "@ignore",
                  "forward_auth_token" : false,
                  "defaults" : { },
                  "responses_defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "max_retry_attempts" : 1,
                  "dependencies" : [ ],
                  "author" : "EPM-RTC-GPT",
                  "created_at" : "@ignore",
                  "updated_at" : "@ignore",
                  "application_type_schema_id" : "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                  "routes" : { }
                }
                """);
    }

    @Test
    void testApplicationWithTypeSchemaCreationAndThenUpdate_Fails_WhenApplicationPropertiesCreatedAndThenFreed() {

        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_files", null, """
                  {
                      "displayName": "test_app",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                       "userRoles": [
                            "Admin"
                       ],
                       "application_properties" : {
                        "property1" : "test property1",
                        "property2" : "test property2"
                       },
                       "forwardAuthToken": true,
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """);
        Assertions.assertEquals(200, response.status());

        response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_files", null, """
                  {
                      "displayName": "test_app",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
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
    void testApplicationWithTypeSchemaCreationAndThenUpdate_Ok_WhenApplicationPropertiesCreatedAndThenUpdated() {

        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_files", null, """
                  {
                      "displayName": "test_app",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                       "userRoles": [
                            "Admin"
                       ],
                       "application_properties" : {
                        "property1" : "test property1",
                        "property2" : "test property2"
                       },
                       "forwardAuthToken": true,
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """);
        Assertions.assertEquals(200, response.status());

        response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_files", null, """
                  {
                      "displayName": "test_app",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                       "userRoles": [
                            "Admin"
                       ],
                       "application_properties" : {
                        "property1" : "test property11",
                        "property2" : "test property21"
                       },
                       "forwardAuthToken": true,
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """);
        Assertions.assertEquals(200, response.status());
    }

    @Test
    void testApplicationWithTypeSchemaCreationAndThenUpdate_Ok_WhenApplicationPropertiesCreatedEmptyAndThenUpdated() {

        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_files", null, """
                  {
                      "displayName": "test_app",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                       "userRoles": [
                            "Admin"
                       ],
                       "application_properties" : null,
                       "forwardAuthToken": true,
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """);
        Assertions.assertEquals(200, response.status());

        response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_files", null, """
                  {
                      "displayName": "test_app",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                       "userRoles": [
                            "Admin"
                       ],
                       "application_properties" : {
                        "property1" : "test property11",
                        "property2" : "test property21"
                       },
                       "forwardAuthToken": true,
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """);
        Assertions.assertEquals(200, response.status());
    }

    @Test
    void testOpenAiApplicationWithTypeSchemaGet_ReturnedInvalid_WhenAppDoesNotConformToSchema() {
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

        response = send(HttpMethod.GET, "/openai/applications/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app_to_fail", null, null, "Api-key", "proxyKey2");

        verifyJsonNotExact(response, 200, """
                  {
                     "display_name" : "test_app",
                     "icon_url" : "https://mydial.somewhere.com/app-icon.svg",
                     "description" : "My application description",
                     "owner" : "EPM-RTC-GPT",
                     "object" : "application",
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
                       "mcp" : true,
                       "max_tokens_supported": true,
                       "max_completion_tokens_supported": false,
                       "custom_temperature_supported": true,
                       "reasoning_efforts": []
                     },
                     "defaults" : { },
                     "responses_defaults" : { },
                     "description_keywords" : [ ],
                     "max_retry_attempts" : 1,
                     "invalid" : true,
                     "application_type_schema_id" : "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                     "viewer_url" : "https://mydial.somewhere.com/custom_application_schemas/viewer",
                     "routes" : {
                       "data_sync" : {
                         "rewritePath" : true,
                         "paths" : [ "/v1/index/search" ],
                         "methods" : [ "POST" ],
                         "maxRetryAttempts" : 1,
                         "order" : 5,
                         "permissions" : [ "WRITE" ],
                         "attachmentPaths" : {
                           "requestBody" : [ ],
                           "responseBody" : [ ]
                         }
                       }
                     }
                }
                """);
    }

    @Test
    void testDeleteApplicationWithTypeSchema_WhenSchemaIsNotfound() {

        Response response = send(HttpMethod.PUT, "/v1/applications/public/test_app_files", null, """
                  {
                      "displayName": "test_app",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                       "userRoles": [
                            "Admin"
                       ],
                       "application_properties" : null,
                       "forwardAuthToken": true,
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """, "authorization", "admin");
        Assertions.assertEquals(200, response.status());

        dial.getProxy().getConfigStore().get()
                .getApplicationTypeSchemas().remove("https://mydial.somewhere.com/custom_application_schemas/specific_application_type");

        response = send(HttpMethod.DELETE, "/v1/applications/public/test_app_files", null, null, "authorization", "admin");
        Assertions.assertEquals(200, response.status());
    }


    @SuppressWarnings("checkstyle:LineLength")
    @DialConfigLocation("dial-config/global-interceptor-config.json")
    @Test
    void testAccessCustomApplicationWithGlobalInterceptor() throws IOException {
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", null, """
                {
                "endpoint": "http://localhost:4848/chat/completions",
                "display_name": "My Custom Application",
                "display_version": "1.0",
                "icon_url": "http://application1/icon.svg",
                "description": "My Custom Application Description"
                }
                """);
        verify(response, 200);

        String responseBody = """
                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"this is a"}}],"usage":null}\r
                data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"very long long"}}],"usage":null}\r
                data: {"id":"chatcmpl-3","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"answer"}}],"usage":null}\r
                data: {"id":"chatcmpl-4","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":"stop","delta":{}}], "usage":{"completion_tokens": 20, "prompt_tokens": 20, "total_tokens": 40}}\r
                data: [DONE]\r
                """;
        AtomicReference<String> capturedInterceptorBody = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            TestWebServer.Handler chatCompletionHandler = request -> {
                MockResponse mockResponse = new MockResponse();
                mockResponse.setResponseCode(200);
                mockResponse.setChunkedBody(responseBody, 200);
                return mockResponse;
            };
            TestWebServer.Handler interceptorHandler = request -> {
                try {
                    capturedInterceptorBody.set(request.getBody().readUtf8());
                    var nextInterceptorRequest = createHttpUriRequest(serverPort,
                            "interceptor", request.getHeader("api-key"));
                    var appResponse = client.execute(nextInterceptorRequest, ResourceBaseTest::toResponse);
                    MockResponse mockResponse = new MockResponse();
                    mockResponse.setResponseCode(appResponse.status());
                    mockResponse.setChunkedBody(appResponse.body(), 200);
                    return mockResponse;
                } catch (Throwable error) {
                    MockResponse mockResponse = new MockResponse();
                    mockResponse.setResponseCode(500);
                    mockResponse.setBody(error.getMessage());
                    return mockResponse;
                }
            };
            server.map(HttpMethod.POST, "/chat/completions", chatCompletionHandler);
            server.map(HttpMethod.POST, "/interceptor/handle", interceptorHandler);
            var request = createHttpUriRequest(serverPort,
                    "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application", "proxyKey1");
            response = client.execute(request, ResourceBaseTest::toResponse);
            verify(response, 200);

            assertEquals("""
                    {"model":"gpt-3-turbo","stream":true,"messages":[{"content":"how are you?","role":"user"}]}""",
                    capturedInterceptorBody.get());
        }
    }

    @Test
    public void testCreateMcpCompatibleApplication() {
        var response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20app", null, """
                {
                "display_name": "My App",
                "display_version": "1.0",
                "icon_url": "http://apprunner/icon.svg",
                "description": "My app Description",
                "mcp": {
                  "endpoint": "http://localhost:7878/mcp",
                  "transport": "HTTP"
                  }
                }
                """);
        verify(response, 200);
    }

    @Test
    void testMcpCall() {
        var response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20app", null, """
                {
                "displayName": "Test Application",
                "description": "For testing purposes only",
                "endpoint": "http://<app host>/openai/deployments/test-app/chat/completions",
                "forwardAuthToken": true
                }
                """);
        verify(response, 200);

        String mcpRequest = """
                {
                   "payload": "foo"
                }
                """;

        Response resp = send(HttpMethod.POST, "/v1/deployments/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20app/mcp",
                null, mcpRequest, "Content-type", "application/json");
        // application doesn't support MCP interface
        assertEquals(400, resp.status());

        response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20app", null, """
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

        response = send(HttpMethod.GET, "/openai/applications/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20app");
        verifyNotExact(response, 200, "\"mcp\":true");

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
                JsonNode node = JsonUtil.read(tree, basePath + ".property1");
                assertNotNull(node);
                assertEquals("foo", node.asText());
                node = JsonUtil.read(tree, basePath + ".property2");
                assertNotNull(node);
                assertEquals("bar", node.asText());
                return new MockResponse().setBody(mcpResponse).setHeader("Content-Type", "application/json");
            } catch (Throwable e) {
                return new MockResponse().setResponseCode(500);
            }
        };
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            resp = send(HttpMethod.POST, "/v1/deployments/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20app/mcp",
                    null, mcpRequest, "Content-type", "application/json");

            assertEquals(200, resp.status());
            assertEquals(mcpResponse, resp.body());
        }
    }


    private HttpUriRequest createHttpUriRequest(int port, String deployment, String apiKey) {
        String uri = "http://127.0.0.1:" + port + "/openai/deployments/" + deployment + "/chat/completions";
        String requestBody = """
                {
                   "model": "gpt-3-turbo",
                   "stream": true,
                   "messages": [
                     {
                       "content": "how are you?",
                       "role": "user"
                     }
                   ]
                 }
                """;
        HttpUriRequest httpUriRequest = new HttpUriRequestBase(HttpMethod.POST.name(), URI.create(uri));
        httpUriRequest.setHeader("api-key", apiKey);
        httpUriRequest.setHeader("content-type", "application/json");
        httpUriRequest.setEntity(new StringEntity(requestBody));
        return httpUriRequest;
    }
}

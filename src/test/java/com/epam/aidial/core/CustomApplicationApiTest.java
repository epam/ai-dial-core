package com.epam.aidial.core;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.data.InvitationLink;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

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
                "etag":"@ignore"
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
                "interceptors": []
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

        // verify can't create new app with provided reference
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
                "etag":"@ignore"
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
                "etag":"@ignore"
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
                "etag":"@ignore"
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
                "interceptors": []
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
                "etag":"@ignore"
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
                   "id" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                   "application" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                   "display_name" : "My Custom Application",
                   "display_version" : "1.0",
                   "icon_url" : "http://application1/icon.svg",
                   "description" : "My Custom Application Description",
                   "reference" : "@ignore",
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
                     "system_prompt" : true,
                     "tools" : false,
                     "seed" : false,
                     "url_attachments" : false,
                     "folder_attachments" : false
                   },
                   "defaults" : { }
                }
                """);

        // verify user1 can list both applications (from config and own)
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
                                "folder_attachments":false
                                },
                            "defaults":{}
                        },
                        {
                            "id" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                            "application" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                            "display_name" : "My Custom Application",
                            "display_version" : "1.0",
                            "icon_url" : "http://application1/icon.svg",
                            "description" : "My Custom Application Description",
                            "reference": "@ignore",
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
                              "system_prompt" : true,
                              "tools" : false,
                              "seed" : false,
                              "url_attachments" : false,
                              "folder_attachments" : false
                            },
                            "defaults" : { }
                          }
                    ],
                    "object":"list"
                }
                """);

        // verify user2 can list both applications (from config and shared)
        response = send(HttpMethod.GET, "/openai/applications", null, null, "Api-key", "proxyKey2");
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
                                "folder_attachments":false
                                },
                            "defaults":{}
                        },
                        {
                            "id" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                            "application" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                            "display_name" : "My Custom Application",
                            "display_version" : "1.0",
                            "icon_url" : "http://application1/icon.svg",
                            "description" : "My Custom Application Description",
                            "reference" : "@ignore",
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
                              "system_prompt" : true,
                              "tools" : false,
                              "seed" : false,
                              "url_attachments" : false,
                              "folder_attachments" : false
                            },
                            "defaults" : { }
                          }
                    ],
                    "object":"list"
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
                    "reviewUrl" : "applications/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/my-custom-application"
                   } ],
                   "resourceTypes" : [ "APPLICATION" ],
                   "rules" : [ {
                     "function" : "EQUAL",
                     "source" : "roles",
                     "targets" : [ "user" ]
                   } ]
                }
                """);

        response = send(HttpMethod.GET, "/v1/applications/public/folder/my-custom-application",
                null, null, "authorization", "admin");
        verify(response, 200);

        response = send(HttpMethod.GET, "/v1/applications/public/folder/my-custom-application",
                null, null, "authorization", "user");
        verify(response, 200);

        // verify listing returns both applications (from config and public)
        response = send(HttpMethod.GET, "/openai/applications", null, null, "authorization", "user");
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
                                "folder_attachments":false
                                },
                            "defaults":{}
                        },
                        {
                            "id" : "applications/public/folder/my-custom-application",
                            "application" : "applications/public/folder/my-custom-application",
                            "display_name" : "My Custom Application",
                            "display_version" : "1.0",
                            "icon_url" : "http://application1/icon.svg",
                            "description" : "My Custom Application Description",
                            "reference" : "@ignore",
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
                              "system_prompt" : true,
                              "tools" : false,
                              "seed" : false,
                              "url_attachments" : false,
                              "folder_attachments" : false
                            },
                            "defaults" : { }
                          }
                    ],
                    "object":"list"
                }
                """);
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
                                "folder_attachments":false
                                },
                            "defaults":{}
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
                        "system_prompt":true,
                        "tools":false,
                        "seed":false,
                        "url_attachments":false,
                        "folder_attachments":false
                    },
                    "defaults":{}
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
                                "folder_attachments":false
                                },
                            "defaults":{}
                        },
                        {
                            "id" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                            "application" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                            "display_name" : "My Custom Application",
                            "display_version" : "1.0",
                            "icon_url" : "http://application1/icon.svg",
                            "description" : "My Custom Application Description",
                            "reference": "@ignore",
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
                              "system_prompt" : true,
                              "tools" : false,
                              "seed" : false,
                              "url_attachments" : false,
                              "folder_attachments" : false
                            },
                            "defaults" : { }
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
                "etag":"@ignore"
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
                "etag":"@ignore"
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
                "interceptors": []
                }
                """);
        Application application1 = ProxyUtil.convertToObject(response.body(), Application.class, true);
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
                "interceptors": []
                }
                """);
        Application application2 = ProxyUtil.convertToObject(response.body(), Application.class, true);
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
        verify(response, 400);

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
                "interceptors": []
                }
                """);
        Application application = ProxyUtil.convertToObject(response.body(), Application.class, true);
        String reference = application.getReference();

        // verify app2 has same reference as app1
        assertEquals(reference1, reference);

        // verify app1 no longer exists
        response = send(HttpMethod.GET, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application1", null, "");
        verify(response, 404);
    }
}

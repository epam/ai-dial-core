package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.Publications;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.UrlUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicationApiTest extends ResourceBaseTest {

    @Test
    void testPublishApplicationWithOauthExternalServiceReencryptsSecret() {
        String plaintextSecret = "pub-plaintext-secret";
        AtomicReference<String> tokenBody = new AtomicReference<>();
        TestWebServer.Handler handler = request -> {
            tokenBody.set(request.getBody().readUtf8());
            return new MockResponse()
                    .setBody("{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":3600}")
                    .setHeader("Content-Type", "application/json");
        };
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response r = send(HttpMethod.PUT, "/v1/applications/%s/pub-ext-app".formatted(bucket), null, """
                    {
                        "endpoint": "http://localhost:7001/v1/x",
                        "display_name": "Pub Ext App",
                        "external_services": {
                            "sf": {
                                "auth_settings": {
                                    "authentication_type": "OAUTH",
                                    "client_id": "cid",
                                    "client_secret": "%s",
                                    "authorization_endpoint": "http://localhost:9876/authorize",
                                    "token_endpoint": "http://localhost:9876/token",
                                    "token_endpoint_auth_method": "client_secret_post"
                                }
                            }
                        }
                    }
                    """.formatted(plaintextSecret));
            assertEquals(200, r.status(), r.body());

            r = send(HttpMethod.POST, "/v1/ops/publication/create", null, """
                    {
                        "name": "Pub ext app",
                        "targetFolder": "public/folder/",
                        "resources": [
                            {"action":"ADD","sourceUrl":"applications/%s/pub-ext-app","targetUrl":"applications/public/folder/pub-ext-app"}
                        ],
                        "rules": [{"source":"roles","function":"TRUE"}]
                    }
                    """.formatted(bucket));
            assertEquals(200, r.status(), r.body());

            r = send(HttpMethod.POST, "/v1/ops/publication/approve", null,
                    "{\"url\":\"publications/" + bucket + "/0123\"}", "authorization", "admin");
            assertEquals(200, r.status(), r.body());

            // OAUTH sign-in against the PUBLISHED app: the secret must re-encrypt once and decrypt
            // back to plaintext at runtime (guards against double-encryption on publish).
            Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                    {
                        "url": "applications/public/folder/pub-ext-app/external_services/sf",
                        "credentials_level": "USER",
                        "authentication_type": "OAUTH",
                        "code": "auth-code"
                    }
                    """, "authorization", "admin");
            assertEquals(200, signIn.status(), signIn.body());
            assertNotNull(tokenBody.get(), "token endpoint should have been called on sign-in");
            assertTrue(tokenBody.get().contains("client_secret=" + plaintextSecret),
                    "published app's external-service secret must decrypt to plaintext, got: " + tokenBody.get());
        }
    }

    @Test
    void testUpdatePublicationWithOauthExternalServiceKeepsSecretSingleEncrypted() {
        // updatePublication re-puts the review app to rewrite links via getApplication()+putApplication();
        // without decrypting on read it double-encrypts the external-service secret. Guards that path.
        String plaintextSecret = "upd-plaintext-secret";
        AtomicReference<String> tokenBody = new AtomicReference<>();
        TestWebServer.Handler handler = request -> {
            tokenBody.set(request.getBody().readUtf8());
            return new MockResponse()
                    .setBody("{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":3600}")
                    .setHeader("Content-Type", "application/json");
        };
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response r = send(HttpMethod.PUT, "/v1/applications/%s/upd-ext-app".formatted(bucket), null, """
                    {
                        "endpoint": "http://localhost:7001/v1/x",
                        "display_name": "Upd Ext App",
                        "external_services": {
                            "sf": {
                                "auth_settings": {
                                    "authentication_type": "OAUTH",
                                    "client_id": "cid",
                                    "client_secret": "%s",
                                    "authorization_endpoint": "http://localhost:9876/authorize",
                                    "token_endpoint": "http://localhost:9876/token",
                                    "token_endpoint_auth_method": "client_secret_post"
                                }
                            }
                        }
                    }
                    """.formatted(plaintextSecret));
            assertEquals(200, r.status(), r.body());

            String resources = """
                    "resources": [
                        {"action":"ADD","sourceUrl":"applications/%s/upd-ext-app","targetUrl":"applications/public/folder/upd-ext-app"}
                    ],
                    "rules": [{"source":"roles","function":"TRUE"}]
                    """.formatted(bucket);

            r = send(HttpMethod.POST, "/v1/ops/publication/create", null,
                    "{\"name\":\"Upd\",\"targetFolder\":\"public/folder/\"," + resources + "}");
            assertEquals(200, r.status(), r.body());

            // Re-submit the pending publication — this routes through updatePublication's link re-put.
            r = send(HttpMethod.POST, "/v1/ops/publication/update", null,
                    "{\"url\":\"publications/" + bucket + "/0123\",\"targetFolder\":\"public/folder/\"," + resources + "}",
                    "authorization", "admin");
            assertEquals(200, r.status(), r.body());

            r = send(HttpMethod.POST, "/v1/ops/publication/approve", null,
                    "{\"url\":\"publications/" + bucket + "/0123\"}", "authorization", "admin");
            assertEquals(200, r.status(), r.body());

            Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                    {
                        "url": "applications/public/folder/upd-ext-app/external_services/sf",
                        "credentials_level": "USER",
                        "authentication_type": "OAUTH",
                        "code": "auth-code"
                    }
                    """, "authorization", "admin");
            assertEquals(200, signIn.status(), signIn.body());
            assertNotNull(tokenBody.get(), "token endpoint should have been called on sign-in");
            assertTrue(tokenBody.get().contains("client_secret=" + plaintextSecret),
                    "secret must stay single-encrypted through publication update, got: " + tokenBody.get());
        }
    }

    private static final String PUBLICATION_REQUEST = """
            {
              "name": "Publication name",
              "targetFolder": "public/folder/",
              "resources": [
                {
                  "action": "ADD",
                  "sourceUrl": "conversations/%s/my/folder/conversation",
                  "targetUrl": "conversations/public/folder/conversation"
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
            """;

    private static final String PUBLICATION_REQUEST_WITH_RULES_ONLY = """
            {
              "name": "Publication name",
              "targetFolder": "public/folder/",
              "rules": [
                {
                  "source": "roles",
                  "function": "EQUAL",
                  "targets": ["user"]
                }
              ]
            }
            """;

    private static final String PUBLICATION_REQUEST_WITH_FILE = """
            {
              "name": "Publication name",
              "targetFolder": "public/folder/",
              "resources": [
                {
                  "action": "ADD",
                  "sourceUrl": "conversations/%s/my/folder/conversation%s",
                  "targetUrl": "conversations/public/folder/conversation%s"
                },
                {
                  "action": "%s",
                  "sourceUrl": "files/%s/file",
                  "targetUrl": "files/public/folder/file"
                }
              ]
            }
            """;

    private static final String PUBLICATION_URL = """
            {
              "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123"
            }
            """;

    private static final String PUBLICATION_RESPONSE = """
            {
              "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
              "name": "Publication name",
              "targetFolder" : "public/folder/",
              "status" : "PENDING",
              "createdAt" : 0,
              "resources" : [ {
                "action": "ADD",
                "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                "targetUrl" : "conversations/public/folder/conversation",
                "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation",
                "publishCredentials" : false
               } ],
              "resourceTypes" : [ "CONVERSATION" ],
              "rules" : [ {
                "function" : "EQUAL",
                "source" : "roles",
                "targets" : [ "user" ]
              } ],
              "author" : "EPM-RTC-GPT"
            }
            """;

    @Test
    void testPublicationCreation() {
        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket));
        verifyJson(response, 200, PUBLICATION_RESPONSE);


        response = operationRequest("/v1/ops/publication/get", PUBLICATION_URL);
        verifyJson(response, 200, PUBLICATION_RESPONSE);

        response = operationRequest("/v1/ops/publication/get", PUBLICATION_URL, "authorization", "admin");
        verifyJson(response, 200, PUBLICATION_RESPONSE);

        response = operationRequest("/v1/ops/publication/get", PUBLICATION_URL, "authorization", "user");
        verify(response, 403);


        response = operationRequest("/v1/ops/publication/list", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/"
                }
                """);

        verifyJson(response, 200, """
                {
                  "publications" : [ {
                    "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                    "name": "Publication name",
                    "targetFolder" : "public/folder/",
                    "status" : "PENDING",
                    "createdAt" : 0,
                    "resourceTypes" : [ "CONVERSATION" ],
                    "author" : "EPM-RTC-GPT"
                  } ]
                }
                """);


        response = send(HttpMethod.GET, "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verifyJson(response, 200, """
                {
                    "id": "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation",
                    "name": "display_name",
                    "model": {"id": "model_id"},
                    "prompt": "system prompt",
                    "temperature": 1,
                    "folderId": "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo",
                    "messages": [],
                    "assistantModelId": "assistantId",
                    "lastActivityDate": 4848683153
                }
                """);

        response = send(HttpMethod.PUT, "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verify(response, 403);

        response = send(HttpMethod.DELETE, "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verify(response, 403);


        response = send(HttpMethod.GET, "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation", null, null, "authorization", "user");
        verify(response, 403);


        response = send(HttpMethod.GET, "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation", null, null, "authorization", "admin");
        verify(response, 200);
    }

    @Test
    void testPublicationCreation_OnlyRules() {

        var response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST_WITH_RULES_ONLY);
        verify(response, 200);
    }

    @Test
    void testDeleteApprovedPublicationWorkflow() {
        ApiKeyData adminAppKey = createAdminAppKey();
        apiKeyStore.assignPerRequestApiKey(adminAppKey);

        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/",
                  "targetFolder": "public/folder/",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                      "targetUrl": "conversations/public/folder/conversation"
                    }
                  ],
                  "rules": []
                }
                """);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "api-key", adminAppKey.getPerRequestKey());
        verify(response, 403);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "targetFolder" : "public/folder/",
                  "status" : "APPROVED",
                  "createdAt" : 0,
                  "resources" : [ {
                    "action": "ADD",
                    "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                    "targetUrl" : "conversations/public/folder/conversation",
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation",
                    "publishCredentials" : false
                   } ],
                   "resourceTypes" : [ "CONVERSATION" ],
                   "rules" : [],
                   "author" : "EPM-RTC-GPT"
                }
                """);

        // verify publication can be listed and has approved status
        response = operationRequest("/v1/ops/publication/list", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/"
                }
                """);
        verifyJson(response, 200, """
                {
                  "publications": [{
                    "url":"publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                    "targetFolder":"public/folder/",
                    "status":"APPROVED",
                    "createdAt":0,
                    "resourceTypes" : [ "CONVERSATION" ],
                    "author" : "EPM-RTC-GPT"
                    }]
                }
                """);

        // initialize delete request by user
        response = operationRequest("/v1/ops/publication/create", """
                {
                    "targetFolder":"public/folder/",
                    "resources": [
                        {
                        "action": "DELETE",
                        "targetUrl": "conversations/public/folder/conversation"
                        }
                    ]
                }
                """);
        verify(response, 200);

        // verify new publication request has status REQUESTED_FOR_DELETION
        response = operationRequest("/v1/ops/publication/list", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/"
                }
                """);
        verifyJson(response, 200, """
                {
                  "publications": [{
                        "url":"publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                        "targetFolder":"public/folder/",
                        "status":"APPROVED",
                        "createdAt":0,
                        "resourceTypes" : [ "CONVERSATION" ],
                        "author" : "EPM-RTC-GPT"
                    },
                    {
                        "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124",
                         "targetFolder":"public/folder/",
                        "status" : "PENDING",
                        "createdAt" : 0,
                        "resourceTypes" : [ "CONVERSATION" ],
                        "author" : "EPM-RTC-GPT"
                      }
                    ]
                }
                """);

        // verify published resource accessible by admin
        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation",
                null, null, "authorization", "admin");
        verify(response, 200);

        // verify published resource accessible by user
        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation",
                null, null, "authorization", "user");
        verify(response, 200);

        // verify admin can list requested for deletion publications
        response = operationRequest("/v1/ops/publication/list", """
                {"url": "publications/public/"}
                """, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "publications": [{
                    "url":"publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124",
                    "targetFolder":"public/folder/",
                    "status":"PENDING",
                    "createdAt":0,
                    "resourceTypes" : [ "CONVERSATION" ],
                    "author" : "EPM-RTC-GPT"
                    }]
                }
                """);

        // delete publication by admin
        response = operationRequest("/v1/ops/publication/approve", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124"
                }
                """, "authorization", "admin");
        verify(response, 200);

        // verify no pending/requested_for_deletion publication remain
        response = operationRequest("/v1/ops/publication/list", """
                {"url": "publications/public/"}
                """, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "publications": []
                }
                """);

        // verify published resource is not accessible by admin
        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation",
                null, null, "authorization", "admin");
        verify(response, 404);

        // verify published resource is not accessible by user
        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation",
                null, null, "authorization", "user");
        verify(response, 404);

        // verify both requests in finalized status
        response = operationRequest("/v1/ops/publication/list", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/"
                }
                """);
        verifyJson(response, 200, """
                {
                  "publications": [{
                        "url":"publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                        "targetFolder":"public/folder/",
                        "status":"APPROVED",
                        "createdAt":0,
                        "resourceTypes" : [ "CONVERSATION" ],
                        "author" : "EPM-RTC-GPT"
                    },
                    {
                        "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124",
                        "targetFolder":"public/folder/",
                        "status":"APPROVED",
                        "createdAt" : 0,
                        "resourceTypes" : [ "CONVERSATION" ],
                        "author" : "EPM-RTC-GPT"
                      }
                    ]
                }
                """);
    }

    @Test
    void testPublicationDeletion() {
        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket));
        verify(response, 200);


        response = operationRequest("/v1/ops/publication/delete", PUBLICATION_URL, "authorization", "user");
        verify(response, 403);

        response = operationRequest("/v1/ops/publication/delete", PUBLICATION_URL);
        verify(response, 200);


        response = send(HttpMethod.GET, "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verify(response, 404);

        response = send(HttpMethod.PUT, "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verify(response, 403);

        response = send(HttpMethod.DELETE, "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verify(response, 403);
    }


    @Test
    void testRejectUserDeletionRequestWorkflow() {
        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", """
                {
                  "targetFolder": "public/folder/",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                      "targetUrl": "conversations/public/folder/conversation"
                    }
                  ],
                  "rules": []
                }
                """);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "targetFolder" : "public/folder/",
                  "status" : "APPROVED",
                  "createdAt" : 0,
                  "resources" : [ {
                    "action": "ADD",
                    "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                    "targetUrl" : "conversations/public/folder/conversation",
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation",
                    "publishCredentials": false
                   } ],
                   "resourceTypes" : [ "CONVERSATION" ],
                   "rules" : [],
                   "author" : "EPM-RTC-GPT"
                }
                """);

        // verify publication can be listed and has approved status
        response = operationRequest("/v1/ops/publication/list", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/"
                }
                """);
        verifyJson(response, 200, """
                {
                  "publications": [{
                    "url":"publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                    "targetFolder":"public/folder/",
                    "status":"APPROVED",
                    "createdAt":0,
                    "resourceTypes" : [ "CONVERSATION" ],
                    "author" : "EPM-RTC-GPT"
                    }]
                }
                """);

        // verify publication notification
        response = operationRequest("/v1/ops/notification/list", "");
        verifyJsonNotExact(response, 200, """
                {"notifications":[
                 {
                    "id":"@ignore",
                    "url":"publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                    "type":"PUBLICATION",
                    "message":"Your request has been approved by admin",
                    "timestamp": "@ignore"
                 }
                ]}
                """);

        // initialize delete request by user (publication owner)
        response = operationRequest("/v1/ops/publication/create", """
                {
                  "targetFolder": "public/folder/",
                  "resources": [
                    {
                      "action": "DELETE",
                      "targetUrl": "conversations/public/folder/conversation"
                    }
                  ],
                  "rules": []
                }
                """);
        verify(response, 200);

        // verify publication has status REQUESTED_FOR_DELETION
        response = operationRequest("/v1/ops/publication/list", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/"
                }
                """);
        verifyJson(response, 200, """
                {
                  "publications": [{
                        "url":"publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                        "targetFolder":"public/folder/",
                        "status":"APPROVED",
                        "createdAt":0,
                        "resourceTypes" : [ "CONVERSATION" ],
                        "author" : "EPM-RTC-GPT"
                    },
                    {
                        "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124",
                        "targetFolder":"public/folder/",
                        "status" : "PENDING",
                        "createdAt" : 0,
                        "resourceTypes" : [ "CONVERSATION" ],
                        "author" : "EPM-RTC-GPT"
                      }
                    ]
                }
                """);

        // verify published resource accessible by admin
        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation",
                null, null, "authorization", "admin");
        verify(response, 200);

        // verify published resource accessible by user
        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation",
                null, null, "authorization", "user");
        verify(response, 200);

        // verify admin can list requested for deletion publications
        response = operationRequest("/v1/ops/publication/list", """
                {"url": "publications/public/"}
                """, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "publications": [{
                    "url":"publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124",
                    "targetFolder" : "public/folder/",
                    "status":"PENDING",
                    "createdAt":0,
                    "resourceTypes" : [ "CONVERSATION" ],
                    "author" : "EPM-RTC-GPT"
                    }]
                }
                """);

        // reject deletion request by admin
        response = operationRequest("/v1/ops/publication/reject", """
                {
                    "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124",
                    "comment": "Bad resources"
                }
                """, "authorization", "admin");
        verify(response, 200);

        // verify no pending/requested_for_deletion publication remain
        response = operationRequest("/v1/ops/publication/list", """
                {"url": "publications/public/"}
                """, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "publications": []
                }
                """);

        // verify deletion request rejected
        response = operationRequest("/v1/ops/publication/list", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/"
                }
                """);
        verifyJson(response, 200, """
                {
                  "publications": [{
                        "url":"publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                        "targetFolder":"public/folder/",
                        "status":"APPROVED",
                        "createdAt":0,
                        "resourceTypes" : [ "CONVERSATION" ],
                        "author" : "EPM-RTC-GPT"
                    },
                    {
                        "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124",
                        "targetFolder" : "public/folder/",
                        "status" : "REJECTED",
                        "createdAt" : 0,
                        "resourceTypes" : [ "CONVERSATION" ],
                        "author" : "EPM-RTC-GPT"
                      }
                    ]
                }
                """);

        response = operationRequest("/v1/ops/notification/list", "");
        verifyJsonNotExact(response, 200, """
                {
                   "notifications":[
                      {
                         "id": "@ignore",
                         "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                         "type": "PUBLICATION",
                         "message": "Your request has been approved by admin",
                         "timestamp": "@ignore"
                      },
                      {
                         "id": "@ignore",
                         "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124",
                         "type": "PUBLICATION",
                         "message": "Your request has been rejected by admin: Bad resources",
                         "timestamp": "@ignore"
                      }
                   ]
                }
                """);

        // verify published resource accessible by admin
        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation",
                null, null, "authorization", "admin");
        verify(response, 200);

        // verify published resource accessible by user
        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation",
                null, null, "authorization", "user");
        verify(response, 200);
    }

    @Test
    void testPublicationWithAddIfAbsent() throws Exception {
        Response response = upload(HttpMethod.PUT, "/v1/files/" + bucket + "/file", null, "text data");
        verify(response, 200);
        String conversationTemplate = """
                {
                "id": "%s",
                "name": "display_name",
                "model": {"id": "model_id"},
                "prompt": "system prompt",
                "temperature": 1,
                "folderId": "%s",
                "messages": [{
                    "role": "user",
                    "content": "what's the file?",
                    "custom_content": {
                        "attachments": [
                          {
                            "type": "text/markdown",
                            "title": "title",
                            "url": "%s"
                          }
                        ]
                    }
                }],
                "assistantModelId": "assistantId",
                "lastActivityDate": 4848683153
                }
                """;
        JsonNode fileResponse = ProxyUtil.MAPPER.readTree(response.body());
        String conversation = conversationTemplate.formatted("conversation_id", "folder1", fileResponse.get("url").asText());

        response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation1", conversation);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST_WITH_FILE.formatted(bucket, "1", "1", "ADD_IF_ABSENT", bucket));
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verifyJsonNotExact(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "name" : "Publication name",
                  "targetFolder" : "public/folder/",
                  "status" : "APPROVED",
                  "createdAt" : 0,
                  "resources" : [ {
                    "action": "ADD",
                    "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation1",
                    "targetUrl" : "conversations/public/folder/conversation1",
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation1",
                    "publishCredentials" : false
                    },
                    {
                    "action": "ADD_IF_ABSENT",
                    "sourceUrl" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/file",
                    "targetUrl" : "files/public/folder/file",
                    "reviewUrl" : "files/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/file",
                    "publishCredentials" : false
                    }
                   ],
                   "resourceTypes" : [ "@ignore", "@ignore" ],
                   "author" : "EPM-RTC-GPT"
                }
                """);


        response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation2", conversation);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST_WITH_FILE.formatted(bucket, "2", "2", "ADD_IF_ABSENT", bucket));
        verify(response, 200);

        String publication = """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124"
                }
                """;
        response = operationRequest("/v1/ops/publication/approve", publication, "authorization", "admin");
        verifyJsonNotExact(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124",
                  "name" : "Publication name",
                  "targetFolder" : "public/folder/",
                  "status" : "APPROVED",
                  "createdAt" : 0,
                  "resources" : [ {
                    "action": "ADD",
                    "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation2",
                    "targetUrl" : "conversations/public/folder/conversation2",
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhQHmtD7fN295EFSG4HiW8Zi/conversation2",
                    "publishCredentials" : false
                    },
                    {
                    "action": "ADD_IF_ABSENT",
                    "sourceUrl" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/file",
                    "targetUrl" : "files/public/folder/file",
                    "reviewUrl" : "files/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhQHmtD7fN295EFSG4HiW8Zi/file",
                    "publishCredentials" : false
                    }
                   ],
                   "resourceTypes" : [ "@ignore", "@ignore" ],
                   "author" : "EPM-RTC-GPT"
                }
                """);

        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation2");
        verifyJsonNotExact(response, 200, conversationTemplate.formatted("conversations/public/folder/conversation2",
                "conversations/public/folder", "files/public/folder/file"));

    }

    @Test
    void testPublicationApprove() {
        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket));
        verify(response, 200);


        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL);
        verify(response, 403);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "user");
        verify(response, 403);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "name" : "Publication name",
                  "targetFolder" : "public/folder/",
                  "status" : "APPROVED",
                  "createdAt" : 0,
                  "resources" : [ {
                    "action": "ADD",
                    "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                    "targetUrl" : "conversations/public/folder/conversation",
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation",
                    "publishCredentials" : false
                   } ],
                   "resourceTypes" : [ "CONVERSATION" ],
                   "rules" : [ {
                     "function" : "EQUAL",
                     "source" : "roles",
                     "targets" : [ "user" ]
                   } ],
                   "author" : "EPM-RTC-GPT"
                }
                """);


        response = send(HttpMethod.GET, "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verify(response, 404);

        response = send(HttpMethod.GET, "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation",
                null, null, "authorization", "admin");
        verify(response, 404);

        response = send(HttpMethod.GET, "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation",
                null, null, "authorization", "user");
        verify(response, 403);


        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation");
        verify(response, 403);

        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation",
                null, null, "authorization", "admin");
        verify(response, 200);

        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation",
                null, null, "authorization", "user");
        verify(response, 200);


        response = send(HttpMethod.DELETE, "/v1/conversations/public/folder/conversation");
        verify(response, 403);

        response = send(HttpMethod.DELETE, "/v1/conversations/public/folder/conversation",
                null, null, "authorization", "user");
        verify(response, 403);
    }

    @Test
    void testPublicationReject() {
        ApiKeyData adminAppKey = createAdminAppKey();
        apiKeyStore.assignPerRequestApiKey(adminAppKey);

        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket));
        verify(response, 200);


        response = operationRequest("/v1/ops/publication/reject", PUBLICATION_URL);
        verify(response, 403);

        response = operationRequest("/v1/ops/publication/reject", PUBLICATION_URL, "authorization", "user");
        verify(response, 403);

        response = operationRequest("/v1/ops/publication/reject", PUBLICATION_URL, "api-key", adminAppKey.getPerRequestKey());
        verify(response, 403);

        response = operationRequest("/v1/ops/publication/reject", PUBLICATION_URL, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "name": "Publication name",
                  "targetFolder" : "public/folder/",
                  "status" : "REJECTED",
                  "createdAt" : 0,
                  "resources" : [ {
                    "action": "ADD",
                    "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                    "targetUrl" : "conversations/public/folder/conversation",
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation",
                    "publishCredentials" : false
                  } ],
                  "resourceTypes" : [ "CONVERSATION" ],
                  "rules" : [ {
                    "function" : "EQUAL",
                    "source" : "roles",
                    "targets" : [ "user" ]
                  } ],
                  "author" : "EPM-RTC-GPT"
                }
                """);


        response = send(HttpMethod.GET, "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verify(response, 404);

        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation");
        verify(response, 404);

        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation",
                null, null, "authorization", "admin");
        verify(response, 404);

        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation",
                null, null, "authorization", "user");
        verify(response, 404);
    }

    @Test
    void testResourceList() {
        Response response = send(HttpMethod.GET, "/v1/metadata/conversations/public/", "permissions=true", null,
                "authorization", "user");
        verifyJson(response, 200, """
                {
                  "name" : null,
                  "parentPath" : null,
                  "bucket" : "public",
                  "url" : "conversations/public/",
                  "nodeType" : "FOLDER",
                  "resourceType" : "CONVERSATION",
                  "permissions" : [ "READ" ],
                  "items" : [ ]
                }
                """);

        response = send(HttpMethod.GET, "/v1/metadata/conversations/public/", "permissions=true", null,
                "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "name" : null,
                  "parentPath" : null,
                  "bucket" : "public",
                  "url" : "conversations/public/",
                  "nodeType" : "FOLDER",
                  "resourceType" : "CONVERSATION",
                  "permissions" : [ "READ", "WRITE", "SHARE" ],
                  "items" : [ ]
                }
                """);


        response = resourceRequest(HttpMethod.PUT, "/my/folder1/conversation1", CONVERSATION_BODY_1);
        verify(response, 200);

        response = resourceRequest(HttpMethod.PUT, "/my/folder2/conversation2", CONVERSATION_BODY_2);
        verify(response, 200);


        response = operationRequest("/v1/ops/publication/create", """
                {
                  "targetFolder": "public/folder1/",
                  "displayAuthor": "dream-team",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/%s/my/folder1/conversation1",
                      "targetUrl": "conversations/public/folder1/conversation1"
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
                {"url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123"}
                """, "authorization", "admin");
        verify(response, 200);


        response = operationRequest("/v1/ops/publication/create", """
                {
                  "targetFolder": "public/folder2/",
                  "displayAuthor": "dream-team",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/%s/my/folder2/conversation2",
                      "targetUrl": "conversations/public/folder2/conversation2"
                    }
                  ],
                  "rules": [
                    {
                      "source": "roles",
                      "function": "EQUAL",
                      "targets": ["user2"]
                    }
                  ]
                }
                """.formatted(bucket));
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/approve", """
                {"url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124"}
                """, "authorization", "admin");
        verify(response, 200);


        response = send(HttpMethod.GET, "/v1/metadata/conversations/public/", "permissions=true", null,
                "authorization", "user");
        verifyJson(response, 200, """
                {
                   "name" : null,
                   "parentPath" : null,
                   "bucket" : "public",
                   "url" : "conversations/public/",
                   "nodeType" : "FOLDER",
                   "resourceType" : "CONVERSATION",
                   "permissions" : [ "READ" ],
                   "items" : [
                   {
                     "name" : "folder1",
                     "parentPath" : null,
                     "bucket" : "public",
                     "url" : "conversations/public/folder1/",
                     "nodeType" : "FOLDER",
                     "resourceType" : "CONVERSATION",
                     "permissions" : [ "READ" ],
                     "items" : null
                   } ]
                 }
                """);

        response = send(HttpMethod.GET, "/v1/metadata/conversations/public/", "recursive=true&permissions=true", null,
                "authorization", "user");
        verifyJsonNotExact(response, 200, """
                {
                  "name" : null,
                  "parentPath" : null,
                  "bucket" : "public",
                  "url" : "conversations/public/",
                  "nodeType" : "FOLDER",
                  "resourceType" : "CONVERSATION",
                  "permissions" : [ "READ" ],
                  "items" : [{
                     "name" : "conversation1",
                     "parentPath" : "folder1",
                     "bucket" : "public",
                     "url" : "conversations/public/folder1/conversation1",
                     "nodeType" : "ITEM",
                     "resourceType" : "CONVERSATION",
                     "permissions" : [ "READ" ],
                     "updatedAt" : "@ignore"
                  } ]
                }
                """);

        response = send(HttpMethod.GET, "/v1/metadata/conversations/public/folder1/conversation1", "permissions=true", null,
                "authorization", "user");
        verifyJsonNotExact(response, 200, """
                {
                    "name": "conversation1",
                    "parentPath": "folder1",
                    "bucket": "public",
                    "url": "conversations/public/folder1/conversation1",
                    "nodeType": "ITEM",
                    "resourceType": "CONVERSATION",
                    "permissions": [
                      "READ"
                    ],
                    "createdAt": "@ignore",
                    "updatedAt": "@ignore",
                    "etag": "@ignore",
                    "author": "dream-team"
                  }
                """);

        response = send(HttpMethod.GET, "/v1/metadata/conversations/public/", "permissions=true", null,
                "authorization", "admin");
        verifyJsonNotExact(response, 200, """
                {
                   "name" : null,
                   "parentPath" : null,
                   "bucket" : "public",
                   "url" : "conversations/public/",
                   "nodeType" : "FOLDER",
                   "resourceType" : "CONVERSATION",
                  "permissions" : [ "READ", "WRITE", "SHARE" ],
                   "items" : [ {
                     "name" : "folder1",
                     "parentPath" : null,
                     "bucket" : "public",
                     "url" : "conversations/public/folder1/",
                     "nodeType" : "FOLDER",
                     "resourceType" : "CONVERSATION",
                     "permissions" : [ "READ", "WRITE", "SHARE" ],
                     "items" : null
                   }, {
                     "name" : "folder2",
                     "parentPath" : null,
                     "bucket" : "public",
                     "url" : "conversations/public/folder2/",
                     "nodeType" : "FOLDER",
                     "resourceType" : "CONVERSATION",
                     "permissions" : [ "READ", "WRITE", "SHARE" ],
                     "items" : null
                   } ]
                 }
                """);

        response = send(HttpMethod.GET, "/v1/metadata/conversations/public/", "recursive=true&permissions=true", null,
                "authorization", "admin");
        verifyJsonNotExact(response, 200, """
                {
                  "name" : null,
                  "parentPath" : null,
                  "bucket" : "public",
                  "url" : "conversations/public/",
                  "nodeType" : "FOLDER",
                  "resourceType" : "CONVERSATION",
                  "permissions" : [ "READ", "WRITE", "SHARE" ],
                  "items" : [{
                    "name" : "conversation1",
                    "parentPath" : "folder1",
                    "bucket" : "public",
                    "url" : "conversations/public/folder1/conversation1",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
                    "permissions" : [ "READ", "WRITE", "SHARE" ],
                    "updatedAt" : "@ignore"
                    },{
                    "name" : "conversation2",
                    "parentPath" : "folder2",
                    "bucket" : "public",
                    "url" : "conversations/public/folder2/conversation2",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
                    "permissions" : [ "READ", "WRITE", "SHARE" ],
                    "updatedAt" : "@ignore"
                    } ]
                }
                """);
    }

    @Test
    void testPublicationList() {
        Response response = operationRequest("/v1/ops/publication/list", """
                {"url": "publications/public/"}
                """);
        verify(response, 403);

        response = operationRequest("/v1/ops/publication/list", """
                {"url": "publications/public/"}
                """, "authorization", "user");
        verify(response, 403);

        response = operationRequest("/v1/ops/publication/list", """
                {"url": "publications/public/"}
                """, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "publications" : [ ]
                }
                """);


        response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket));
        verify(response, 200);


        response = operationRequest("/v1/ops/publication/list", """
                {"url": "publications/public/"}
                """);
        verify(response, 403);

        response = operationRequest("/v1/ops/publication/list", """
                {"url": "publications/public/"}
                """, "authorization", "user");
        verify(response, 403);

        response = operationRequest("/v1/ops/publication/list", """
                {"url": "publications/public/"}
                """, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "publications" : [ {
                    "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                    "name" : "Publication name",
                    "targetFolder" : "public/folder/",
                    "status" : "PENDING",
                    "createdAt" : 0,
                    "resourceTypes" : [ "CONVERSATION" ],
                    "author" : "EPM-RTC-GPT"
                  } ]
                 }
                """);


        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verify(response, 200);


        response = operationRequest("/v1/ops/publication/list", """
                {"url": "publications/public/"}
                """, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "publications" : [ ]
                }
                """);
    }

    @Test
    void testPublicationToForbiddenFolder() {
        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", """
                {
                  "targetFolder": "public/folder/",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/%s/my/folder/conversation",
                      "targetUrl": "conversations/public/folder/conversation"
                    }
                  ],
                  "rules": [
                    {
                      "source": "title",
                      "function": "CONTAIN",
                      "targets": ["Engineer"]
                    }
                  ]
                }
                """.formatted(bucket));
        verifyJson(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "targetFolder" : "public/folder/",
                  "status" : "PENDING",
                  "createdAt" : 0,
                  "resources" : [ {
                    "action": "ADD",
                    "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                    "targetUrl" : "conversations/public/folder/conversation",
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation",
                    "publishCredentials" : false
                   } ],
                  "resourceTypes" : [ "CONVERSATION" ],
                  "rules" : [ {
                    "function" : "CONTAIN",
                    "source" : "title",
                    "targets" : [ "Engineer" ]
                  } ],
                  "author" : "EPM-RTC-GPT"
                }
                """);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "targetFolder" : "public/folder/",
                  "status" : "APPROVED",
                  "createdAt" : 0,
                  "resources" : [ {
                    "action": "ADD",
                    "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                    "targetUrl" : "conversations/public/folder/conversation",
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation",
                    "publishCredentials" : false
                   } ],
                   "resourceTypes" : [ "CONVERSATION" ],
                   "rules" : [ {
                    "function" : "CONTAIN",
                    "source" : "title",
                    "targets" : [ "Engineer" ]
                   } ],
                   "author" : "EPM-RTC-GPT"
                }
                """);

        response = operationRequest("/v1/ops/publication/create", """
                {
                  "targetFolder": "public/folder/folder2/",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/%s/my/folder/conversation",
                      "targetUrl": "conversations/public/folder/folder2/conversation"
                    }
                  ],
                  "rules": [
                    {
                      "source": "title",
                      "function": "CONTAIN",
                      "targets": ["Engineer"]
                    }
                  ]
                }
                """.formatted(bucket));
        verify(response, 403);
    }

    @Test
    void listRules() {
        Response response = operationRequest("/v1/ops/publication/rule/list", """
                {"url": ""}
                """);
        verify(response, 400);

        response = operationRequest("/v1/ops/publication/rule/list", """
                {"url": "public"}
                """);
        verify(response, 400);

        response = operationRequest("/v1/ops/publication/rule/list", """
                {"url": "public/"}
                """);
        verifyJson(response, 200, """
                {
                  "rules" : { }
                }
                """);

        response = operationRequest("/v1/ops/publication/rule/list", """
                {"url": "public/"}
                """, "authorization", "user");
        verifyJson(response, 200, """
                {
                  "rules" : { }
                }
                """);

        response = operationRequest("/v1/ops/publication/rule/list", """
                {"url": "public/"}
                """, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "rules" : { }
                }
                """);

        response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket));
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verify(response, 200);


        response = operationRequest("/v1/ops/publication/rule/list", """
                {"url": "public/folder/"}
                """, "authorization", "user");
        verifyJson(response, 200, """
                {
                  "rules" : {
                    "public/folder/" : [ {
                      "function" : "EQUAL",
                      "source" : "roles",
                      "targets" : [ "user" ]
                    } ]
                  }
                }
                """);

        response = operationRequest("/v1/ops/publication/rule/list", """
                {"url": "public/folder/"}
                """, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "rules" : {
                    "public/folder/" : [ {
                      "function" : "EQUAL",
                      "source" : "roles",
                      "targets" : [ "user" ]
                    } ]
                  }
                }
                """);

        response = operationRequest("/v1/ops/publication/rule/list", """
                {"url": "public/folder/"}
                """);
        verify(response, 403);
    }

    @Test
    void testPublishedResourceList() {
        // verify no published resource
        Response response = operationRequest("/v1/ops/publication/resource/list", """
                {"resourceTypes": ["CONVERSATION"]}
                """);
        verify(response, 200, "[]");

        response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        // create publication request
        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket));
        verify(response, 200);

        // verify admin can view publication request
        response = operationRequest("/v1/ops/publication/list", """
                {"url": "publications/public/"}
                """, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "publications" : [ {
                    "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                    "name" : "Publication name",
                    "targetFolder" : "public/folder/",
                    "status" : "PENDING",
                    "createdAt" : 0,
                    "resourceTypes" : [ "CONVERSATION" ],
                    "author" : "EPM-RTC-GPT"
                  } ]
                 }
                """);

        // verify no published resources (due to PENDING publication request)
        response = operationRequest("/v1/ops/publication/resource/list", """
                {"resourceTypes": ["CONVERSATION"]}
                """);
        verify(response, 200, "[]");

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verify(response, 200);

        // verify published resource can be listed
        response = operationRequest("/v1/ops/publication/resource/list", """
                {"resourceTypes": ["CONVERSATION"]}
                """);
        verifyJson(response, 200, """
                [ {
                  "name" : "conversation",
                  "parentPath" : "my/folder",
                  "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                  "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                  "nodeType" : "ITEM",
                  "resourceType" : "CONVERSATION"
                } ]
                """);
    }

    @Test
    void testPublicationInRootFolderWithRules() {
        Response response = operationRequest("/v1/ops/publication/create", """
                {
                  "targetFolder": "public/",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/%s/my/folder/conversation",
                      "targetUrl": "conversations/public/conversation"
                    }
                  ],
                  "rules": [
                    {
                      "source": "title",
                      "function": "CONTAIN",
                      "targets": ["Engineer"]
                    }
                  ]
                }
                """.formatted(bucket));
        verify(response, 400);
    }

    @Test
    void testPublicationWithRulesOnly() {
        Response response = operationRequest("/v1/ops/publication/create", """
                {
                  "targetFolder": "public/folder/",
                  "resources": [],
                  "rules": [
                    {
                      "source": "title",
                      "function": "CONTAIN",
                      "targets": ["Engineer"]
                    }
                  ]
                }
                """);
        verifyJson(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "targetFolder" : "public/folder/",
                  "status" : "PENDING",
                  "createdAt" : 0,
                  "resources": [],
                  "resourceTypes" : [ ],
                  "rules": [
                    {
                      "function": "CONTAIN",
                      "source": "title",
                      "targets": ["Engineer"]
                    }
                  ],
                  "author" : "EPM-RTC-GPT"
                }
                """);
    }

    @Test
    void testPublicationWithDuplicateResources() {
        Response response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", """
                {
                  "targetFolder": "public/folder/",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/%s/folder/conversation",
                      "targetUrl": "conversations/public/folder/conversation"
                    },
                    {
                      "action": "DELETE",
                      "targetUrl": "conversations/public/folder/conversation"
                    }
                  ],
                  "rules": [
                    {
                      "source": "title",
                      "function": "CONTAIN",
                      "targets": ["Engineer"]
                    }
                  ]
                }
                """.formatted(bucket));
        verify(response, 400);
    }

    @Test
    void testPublicationWithForbiddenResource() {
        Response response = send(HttpMethod.PUT, "/v1/conversations/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder/conversation",
                null, CONVERSATION_BODY_1, "Api-key", "proxyKey2");
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", """
                {
                  "targetFolder": "public/",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/my/folder/conversation",
                      "targetUrl": "conversations/public/conversation"
                    }
                  ]
                }
                """);
        verify(response, 403);
    }

    @Test
    void testPublicationWithoutResourcesAndRules() {
        Response response = operationRequest("/v1/ops/publication/create", """
                {
                  "targetFolder": "public/",
                  "resources": []
                }
                """);
        verify(response, 400);
    }

    @Test
    void testPublicationRuleWithoutTargets() {
        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", """
                {
                      "name": "Publication name",
                      "targetFolder": "public/folder/",
                      "resources": [
                        {
                          "action": "ADD",
                          "sourceUrl": "conversations/%s/my/folder/conversation",
                          "targetUrl": "conversations/public/folder/conversation"
                        }
                      ],
                      "rules": [
                        {
                          "source": "roles",
                          "function": "TRUE",
                          "targets": []
                        }
                      ]
                    }
                """.formatted(bucket));
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", """
                {
                      "name": "Publication name",
                      "targetFolder": "public/folder/",
                      "resources": [
                        {
                          "action": "ADD",
                          "sourceUrl": "conversations/%s/my/folder/conversation",
                          "targetUrl": "conversations/public/folder/conversation"
                        }
                      ],
                      "rules": [
                        {
                          "source": "roles",
                          "function": "TRUE"
                        }
                      ]
                    }
                """.formatted(bucket));
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", """
                {
                      "name": "Publication name",
                      "targetFolder": "public/folder/",
                      "resources": [
                        {
                          "action": "ADD",
                          "sourceUrl": "conversations/%s/my/folder/conversation",
                          "targetUrl": "conversations/public/folder/conversation"
                        }
                      ],
                      "rules": [
                        {
                          "source": "roles",
                          "function": "CONTAIN",
                          "targets": []
                        }
                      ]
                    }
                """.formatted(bucket));
        verify(response, 400, "Rule CONTAIN does not have targets");

        response = operationRequest("/v1/ops/publication/create", """
                {
                      "name": "Publication name",
                      "targetFolder": "public/folder/",
                      "resources": [
                        {
                          "action": "ADD",
                          "sourceUrl": "conversations/%s/my/folder/conversation",
                          "targetUrl": "conversations/public/folder/conversation"
                        }
                      ],
                      "rules": [
                        {
                          "source": "roles",
                          "function": "CONTAIN"
                        }
                      ]
                    }
                """.formatted(bucket));
        verify(response, 400, "Rule CONTAIN does not have targets");
    }

    @Test
    void testApplicationWithTypeSchemaPublish_Ok_FilesAccessible() {
        Response response = upload(HttpMethod.PUT, "/v1/files/%s/test_file.txt".formatted(bucket), null, """
                  Test1
                """);

        Assertions.assertEquals(200, response.status());

        response = upload(HttpMethod.PUT, "/v1/files/%s/xyz/test_file.txt".formatted(bucket), null, """
                  Test2
                """);

        Assertions.assertEquals(200, response.status());

        response = send(HttpMethod.PUT, "/v1/applications/%s/test_app".formatted(bucket), null, """
                  {
                      "displayName": "test_app",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                      "applicationProperties": {
                        "property1": "test property1",
                        "property2": "test property2",
                        "property3": [
                                "files/%s/test_file.txt",
                                "files/%s/xyz/test_file.txt"
                        ]
                       },
                       "userRoles": [
                            "Admin"
                       ],
                       "forwardAuthToken": true,
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """.formatted(bucket, bucket));
        Assertions.assertEquals(200, response.status());

        response = operationRequest("/v1/ops/publication/create", """
                {
                      "name": "Publication of my application",
                      "targetFolder": "public/folder/",
                      "resources": [
                        {
                          "action": "ADD",
                          "sourceUrl": "applications/%s/test_app",
                          "targetUrl": "applications/public/folder/with_apps/test_app"
                        }
                      ],
                      "rules": [
                        {
                          "source": "roles",
                          "function": "TRUE"
                        }
                      ]
                    }
                """.formatted(bucket));
        String correctResponse = """
                {
                  "url" : "publications/%s/0123",
                  "name" : "Publication of my application",
                  "targetFolder" : "public/folder/",
                  "status" : "PENDING",
                  "createdAt" : 0,
                  "resources" : [ {
                    "action" : "ADD",
                    "sourceUrl" : "applications/%s/test_app",
                    "targetUrl" : "applications/public/folder/with_apps/test_app",
                    "reviewUrl" : "applications/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/with_apps/test_app",
                    "publishCredentials" : false
                  }],
                  "resourceTypes" : [ "APPLICATION" ],
                  "rules" : [ {
                    "function" : "TRUE",
                    "source" : "roles",
                    "targets" : null
                  } ],
                  "author" : "EPM-RTC-GPT"
                }""".formatted(bucket, bucket);


        verifyJsonNotExact(response, 200, correctResponse);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verify(response, 200);

    }

    @Test
    void testApplicationWithTypeSchemaPublish_Ok_FolderWithSubfolder() throws JsonProcessingException {

        List<String> filePaths =
                List.of("/v1/files/%s/xyz/test_file1.txt", "/v1/files/%s/xyz/test_file2.txt", "/v1/files/%s/xyz/abc/test_file1.txt", "/v1/files/%s/xyz/abc/test_file2.txt",
                        "/v1/files/%s/some/xyz/abc/test_file1.txt", "/v1/files/%s/some/xyz/abc/test_file2.txt", "/v1/files/%s/another/xyz");

        Response response;
        for (String filePath : filePaths) {
            response = upload(HttpMethod.PUT, filePath.formatted(bucket), null, "Test");
            Assertions.assertEquals(200, response.status());
        }

        response = send(HttpMethod.PUT, "/v1/applications/%s/test_app2".formatted(bucket), null, """
                  {
                      "displayName": "test_app2",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                      "applicationProperties": {
                        "property1": "test property1",
                        "property2": "test property2",
                        "property3": [
                                "files/%s/xyz/", "files/%s/some/xyz/", "files/%s/another/xyz"
                        ]
                       },
                       "userRoles": [
                            "Admin"
                       ],
                       "forwardAuthToken": true,
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """.formatted(bucket, bucket, bucket));
        Assertions.assertEquals(200, response.status());

        response = operationRequest("/v1/ops/publication/create", """
                {
                      "name": "Publication of my application",
                      "targetFolder": "public/folder/",
                      "resources": [
                        {
                          "action": "ADD",
                          "sourceUrl": "applications/%s/test_app2",
                          "targetUrl": "applications/public/folder/with_apps/test_app2"
                        }
                      ],
                      "rules": [
                        {
                          "source": "roles",
                          "function": "TRUE"
                        }
                      ]
                    }
                """.formatted(bucket));
        String correctResponse = """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "name" : "Publication of my application",
                  "targetFolder" : "public/folder/",
                  "status" : "PENDING",
                  "createdAt" : 0,
                  "resources" : [ {
                            "action" : "ADD",
                            "sourceUrl" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app2",
                            "targetUrl" : "applications/public/folder/with_apps/test_app2",
                            "reviewUrl" : "applications/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/with_apps/test_app2",
                            "publishCredentials" : false
                          }
                  ],
                  "resourceTypes" : [ "APPLICATION" ],
                  "rules" : [ {
                    "function" : "TRUE",
                    "source" : "roles",
                    "targets" : null
                  } ],
                  "author" : "EPM-RTC-GPT"
                }""";


        verifyJsonNotExact(response, 200, correctResponse);

        JsonNode responseJson = ProxyUtil.MAPPER.readTree(response.body());
        JsonNode resources = responseJson.get("resources");

        String reviewUrl = resources.get(0).get("reviewUrl").asText();
        response = send(HttpMethod.GET, "/v1/" + reviewUrl, null, null, "authorization", "admin");
        correctResponse = """
                {
                  "name" : "applications/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/with_apps/test_app2",
                  "display_name" : "test_app2",
                  "icon_url" : "https://mydial.somewhere.com/app-icon.svg",
                  "description" : "My application description",
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
                  "application_properties" : {
                    "property1" : "test property1",
                    "property2" : "test property2",
                    "property3" : [ "files/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/with_apps/.test_app2/xyz/",
                    "files/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/with_apps/.test_app2/xyz_2/",
                    "files/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/with_apps/.test_app2/xyz_3" ]
                  },
                  "application_type_schema_id" : "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                  "routes" : { }
                }""";
        verifyJsonNotExact(response, 200, correctResponse);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verify(response, 200);

        String targetUrl = resources.get(0).get("targetUrl").asText();
        response = send(HttpMethod.GET, "/v1/" + targetUrl, null, null, "authorization", "admin");
        correctResponse = """
                {
                  "name" : "applications/public/folder/with_apps/test_app2",
                  "display_name" : "test_app2",
                  "icon_url" : "https://mydial.somewhere.com/app-icon.svg",
                  "description" : "My application description",
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
                  "application_properties" : {
                    "property1" : "test property1",
                    "property2" : "test property2",
                    "property3" : [ "files/public/folder/with_apps/.test_app2/xyz/",
                    "files/public/folder/with_apps/.test_app2/xyz_2/",
                    "files/public/folder/with_apps/.test_app2/xyz_3" ]
                  },
                  "application_type_schema_id" : "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                  "routes" : { }
                }""";
        verifyJsonNotExact(response, 200, correctResponse);

        // After approval, verify files exist at target paths
        response = send(HttpMethod.GET, "/v1/metadata/files/public/folder/with_apps/.test_app2/", "recursive=true", null, "authorization", "admin");
        verify(response, 200);
        responseJson = ProxyUtil.MAPPER.readTree(response.body());
        List<String> files = new ArrayList<>();
        for (var item : responseJson.get("items")) {
            String url = item.get("url").textValue();
            if (!url.endsWith("/")) {
                files.add(url);
            }
        }
        assertEquals(7, files.size());
        List<String> appFiles = List.of("files/public/folder/with_apps/.test_app2/xyz/",
                "files/public/folder/with_apps/.test_app2/xyz_2/",
                "files/public/folder/with_apps/.test_app2/xyz_3");
        int count = 0;
        for (var file : files) {
            for (var parent : appFiles) {
                if (file.startsWith(parent)) {
                    count++;
                    break;
                }
            }
        }
        assertEquals(count, files.size(), "Application files are missed");
    }

    @Test
    void testApplicationWithTypeSchemaPublish_Ok_FilesInRootOfUserBucket() throws JsonProcessingException {

        List<String> filePaths = List.of("/v1/files/%s/test.txt", "/v1/files/%s/test/test.txt");

        Response response;
        for (String filePath : filePaths) {
            response = upload(HttpMethod.PUT, filePath.formatted(bucket), null, "Test");
            Assertions.assertEquals(200, response.status());
        }

        response = send(HttpMethod.PUT, "/v1/applications/%s/test_app3".formatted(bucket), null, """
                  {
                      "displayName": "test_app2",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                      "applicationProperties": {
                        "property1": "test property1",
                        "property2": "test property2",
                        "property3": [
                                "files/%s/test.txt", "files/%s/test/test.txt"
                        ]
                       },
                       "userRoles": [
                            "Admin"
                       ],
                       "forwardAuthToken": true,
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """.formatted(bucket, bucket));
        Assertions.assertEquals(200, response.status());

        response = operationRequest("/v1/ops/publication/create", """
                {
                      "name": "Publication of my application",
                      "targetFolder": "public/",
                      "resources": [
                        {
                          "action": "ADD",
                          "sourceUrl": "applications/%s/test_app3",
                          "targetUrl": "applications/public/abc_app"
                        }
                      ]
                    }
                """.formatted(bucket));
        String correctResponse = """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "name" : "Publication of my application",
                  "targetFolder" : "public/",
                  "status" : "PENDING",
                  "createdAt" : 0,
                  "resources" : [ {
                            "action" : "ADD",
                            "sourceUrl" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app3",
                            "targetUrl" : "applications/public/abc_app",
                            "reviewUrl" : "applications/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/abc_app",
                            "publishCredentials" : false
                          }
                  ],
                  "resourceTypes" : [ "APPLICATION" ],
                  "author" : "EPM-RTC-GPT"
                }""";


        verifyJsonNotExact(response, 200, correctResponse);

        JsonNode responseJson = ProxyUtil.MAPPER.readTree(response.body());
        JsonNode resources = responseJson.get("resources");

        String reviewUrl = resources.get(0).get("reviewUrl").asText();
        response = send(HttpMethod.GET, "/v1/" + reviewUrl, null, null, "authorization", "admin");
        correctResponse = """
                {
                  "name" : "applications/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/abc_app",
                  "display_name" : "test_app2",
                  "icon_url" : "https://mydial.somewhere.com/app-icon.svg",
                  "description" : "My application description",
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
                  "application_properties" : {
                    "property1" : "test property1",
                    "property2" : "test property2",
                    "property3" : [ "files/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/.abc_app/test.txt",
                    "files/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/.abc_app/test_2.txt" ]
                  },
                  "application_type_schema_id" : "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                  "routes" : { }
                }""";
        verifyJsonNotExact(response, 200, correctResponse);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verify(response, 200);

        String targetUrl = resources.get(0).get("targetUrl").asText();
        response = send(HttpMethod.GET, "/v1/" + targetUrl, null, null, "authorization", "admin");
        correctResponse = """
                {
                  "name" : "applications/public/abc_app",
                  "display_name" : "test_app2",
                  "icon_url" : "https://mydial.somewhere.com/app-icon.svg",
                  "description" : "My application description",
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
                  "application_properties" : {
                    "property1" : "test property1",
                    "property2" : "test property2",
                    "property3" : [ "files/public/.abc_app/test.txt", "files/public/.abc_app/test_2.txt" ]
                  },
                  "application_type_schema_id" : "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                  "routes" : { }
                }""";
        verifyJsonNotExact(response, 200, correctResponse);
    }

    @Test
    void testApplicationWithTypeSchemaPublish_Ok_FoldersInRootOfUserBucket() throws JsonProcessingException {

        List<String> filePaths = List.of("/v1/files/%s/test/test.txt", "/v1/files/%s/test1/test"

        );

        Response response;
        for (String filePath : filePaths) {
            response = upload(HttpMethod.PUT, filePath.formatted(bucket), null, "Test");
            Assertions.assertEquals(200, response.status());
        }

        response = send(HttpMethod.PUT, "/v1/applications/%s/test".formatted(bucket), null, """
                  {
                      "displayName": "test",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                      "applicationProperties": {
                        "property1": "test property1",
                        "property2": "test property2",
                        "property3": [
                                "files/%s/test/", "files/%s/test1/test"
                        ]
                       },
                       "userRoles": [
                            "Admin"
                       ],
                       "forwardAuthToken": true,
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """.formatted(bucket, bucket));
        Assertions.assertEquals(200, response.status());

        response = operationRequest("/v1/ops/publication/create", """
                {
                      "name": "Publication of my application",
                      "targetFolder": "public/",
                      "resources": [
                        {
                          "action": "ADD",
                          "sourceUrl": "applications/%s/test",
                          "targetUrl": "applications/public/test"
                        }
                      ]
                    }
                """.formatted(bucket));
        String correctResponse = """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "name" : "Publication of my application",
                  "targetFolder" : "public/",
                  "status" : "PENDING",
                  "createdAt" : 0,
                  "resources" : [ {
                            "action" : "ADD",
                            "sourceUrl" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test",
                            "targetUrl" : "applications/public/test",
                            "reviewUrl" : "applications/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/test",
                            "publishCredentials" : false
                          }
                  ],
                  "resourceTypes" : [ "APPLICATION" ],
                  "author" : "EPM-RTC-GPT"
                }""";


        verifyJsonNotExact(response, 200, correctResponse);

        JsonNode responseJson = ProxyUtil.MAPPER.readTree(response.body());
        JsonNode resources = responseJson.get("resources");

        String reviewUrl = resources.get(0).get("reviewUrl").asText();
        response = send(HttpMethod.GET, "/v1/" + reviewUrl, null, null, "authorization", "admin");
        correctResponse = """
                {
                  "name" : "applications/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/test",
                  "display_name" : "test",
                  "icon_url" : "https://mydial.somewhere.com/app-icon.svg",
                  "description" : "My application description",
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
                  "application_properties" : {
                    "property1" : "test property1",
                    "property2" : "test property2",
                    "property3" : [ "files/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/.test/test/",
                    "files/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/.test/test_2" ]
                  },
                  "application_type_schema_id" : "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                  "routes" : { }
                }""";
        verifyJsonNotExact(response, 200, correctResponse);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verify(response, 200);

        String targetUrl = resources.get(0).get("targetUrl").asText();
        response = send(HttpMethod.GET, "/v1/" + targetUrl, null, null, "authorization", "admin");
        correctResponse = """
                {
                  "name" : "applications/public/test",
                  "display_name" : "test",
                  "icon_url" : "https://mydial.somewhere.com/app-icon.svg",
                  "description" : "My application description",
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
                  "application_properties" : {
                    "property1" : "test property1",
                    "property2" : "test property2",
                    "property3" : [ "files/public/.test/test/", "files/public/.test/test_2" ]
                  },
                  "application_type_schema_id" : "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                  "routes" : { }
                }""";
        verifyJsonNotExact(response, 200, correctResponse);
    }

    @Test
    void testUnpublishSchemaRichApp() throws JsonProcessingException {
        testPublishSchemaRichApp("0123", "2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo");
        Response response = operationRequest("/v1/ops/publication/create", """
                {
                      "name": "Unpublish my application",
                      "targetFolder": "public/",
                      "resources": [
                        {
                          "action": "DELETE",
                          "targetUrl": "applications/public/xyz%20app%204"
                        }
                      ]
                    }
                """);
        verify(response, 200);
        response = operationRequest("/v1/ops/publication/approve", """
            {
              "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124"
            }
            """, "authorization", "admin");
        verify(response, 200);
        testPublishSchemaRichApp("0125", "2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhG9cWs5cubLjt6DVqa4wmnj");
    }

    private void testPublishSchemaRichApp(String pubId, String reviewBucket) throws JsonProcessingException {

        List<String> filePaths = List.of(
                "Unt 26__0.0.1/generate.json",
                "Unt 26__0.0.1/nodes/1743404608.101931_1.json",
                "abc/Unt 26__0.0.1"
        );
        String basePath = "/v1/files/%s/appdata/mindmap/".formatted(bucket);
        Response response;
        for (String filePath : filePaths) {
            String resourceUrl = basePath + UrlUtil.encodePath(filePath);
            response = upload(HttpMethod.PUT, resourceUrl, null, "Test");
            Assertions.assertEquals(200, response.status());
        }

        response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test%20app%202", null, """
                  {
                      "displayName": "test%20app%202",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                      "applicationProperties": {
                        "property1": "test property1",
                        "property2": "test property2",
                        "property3": [
                                "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/mindmap/Unt%2026__0.0.1/",
                                "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/mindmap/abc/Unt%2026__0.0.1"
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

        response = operationRequest("/v1/ops/publication/create", """
                {
                      "name": "Publication of my application",
                      "targetFolder": "public/",
                      "resources": [
                        {
                          "action": "ADD",
                          "sourceUrl": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test%20app%202",
                          "targetUrl": "applications/public/xyz%20app%204"
                        }
                      ]
                    }
                """);
        String correctResponse = """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/%s",
                  "name" : "Publication of my application",
                  "targetFolder" : "public/",
                  "status" : "PENDING",
                  "createdAt" : 0,
                  "resources" : [ {
                       "action" : "ADD",
                       "sourceUrl" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test%%20app%%202",
                       "targetUrl" : "applications/public/xyz%%20app%%204",
                       "reviewUrl" : "applications/%s/xyz%%20app%%204",
                       "publishCredentials" : false
                     }],
                  "resourceTypes" : [ "APPLICATION" ],
                  "author" : "EPM-RTC-GPT"
                }""".formatted(pubId, reviewBucket);


        verifyJsonNotExact(response, 200, correctResponse);

        response = operationRequest("/v1/ops/publication/approve", """
            {
              "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/%s"
            }
            """.formatted(pubId), "authorization", "admin");
        verify(response, 200);

        JsonNode responseJson = ProxyUtil.MAPPER.readTree(response.body());
        JsonNode resources = responseJson.get("resources");
        String targetUrl = resources.get(0).get("targetUrl").asText();
        response = send(HttpMethod.GET, "/v1/" + targetUrl, null, null, "authorization", "admin");
        correctResponse = """
                {
                   "name" : "applications/public/xyz%20app%204",
                   "display_name" : "test%20app%202",
                   "icon_url" : "https://mydial.somewhere.com/app-icon.svg",
                   "description" : "My application description",
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
                   "application_properties" : {
                     "property1" : "test property1",
                     "property2" : "test property2",
                     "property3" : [ "files/public/.xyz%20app%204/Unt%2026__0.0.1/", "files/public/.xyz%20app%204/Unt%2026__0.0_2.1" ]
                   },
                   "application_type_schema_id" : "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                   "routes" : { }
                 }""";
        verifyJsonNotExact(response, 200, correctResponse);

    }

    @Test
    void testApplicationWithTypeSchemaPublish_Ok_MindMapCase() throws JsonProcessingException {

        List<String> filePaths = List.of(
                "Unt 26__0.0.1/generate.json",
                "Unt 26__0.0.1/nodes/1743404608.101931_1.json",
                "abc/Unt 26__0.0.1"
        );
        String basePath = "/v1/files/%s/appdata/mindmap/".formatted(bucket);
        Response response;
        for (String filePath : filePaths) {
            String resourceUrl = basePath + UrlUtil.encodePath(filePath);
            response = upload(HttpMethod.PUT, resourceUrl, null, "Test");
            Assertions.assertEquals(200, response.status());
        }

        response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test%20app%202", null, """
                  {
                      "displayName": "test%20app%202",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                      "applicationProperties": {
                        "property1": "test property1",
                        "property2": "test property2",
                        "property3": [
                                "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/mindmap/Unt%2026__0.0.1/",
                                "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/mindmap/abc/Unt%2026__0.0.1"
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

        response = operationRequest("/v1/ops/publication/create", """
                {
                      "name": "Publication of my application",
                      "targetFolder": "public/",
                      "resources": [
                        {
                          "action": "ADD",
                          "sourceUrl": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test%20app%202",
                          "targetUrl": "applications/public/xyz%20app%204"
                        }
                      ]
                    }
                """);
        String correctResponse = """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "name" : "Publication of my application",
                  "targetFolder" : "public/",
                  "status" : "PENDING",
                  "createdAt" : 0,
                  "resources" : [ {
                       "action" : "ADD",
                       "sourceUrl" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test%20app%202",
                       "targetUrl" : "applications/public/xyz%20app%204",
                       "reviewUrl" : "applications/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/xyz%20app%204",
                       "publishCredentials" : false
                     }],
                  "resourceTypes" : [ "APPLICATION" ],
                  "author" : "EPM-RTC-GPT"
                }""";


        verifyJsonNotExact(response, 200, correctResponse);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verify(response, 200);

        JsonNode responseJson = ProxyUtil.MAPPER.readTree(response.body());
        JsonNode resources = responseJson.get("resources");
        String targetUrl = resources.get(0).get("targetUrl").asText();
        response = send(HttpMethod.GET, "/v1/" + targetUrl, null, null, "authorization", "admin");
        correctResponse = """
                {
                   "name" : "applications/public/xyz%20app%204",
                   "display_name" : "test%20app%202",
                   "icon_url" : "https://mydial.somewhere.com/app-icon.svg",
                   "description" : "My application description",
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
                   "application_properties" : {
                     "property1" : "test property1",
                     "property2" : "test property2",
                     "property3" : [ "files/public/.xyz%20app%204/Unt%2026__0.0.1/", "files/public/.xyz%20app%204/Unt%2026__0.0_2.1" ]
                   },
                   "application_type_schema_id" : "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                   "routes" : { }
                 }""";
        verifyJsonNotExact(response, 200, correctResponse);
        List<String> targetFiles = List.of(
                "files/public/.xyz%20app%204/Unt%2026__0.0.1/nodes/1743404608.101931_1.json",
                "files/public/.xyz%20app%204/Unt%2026__0.0.1/generate.json",
                "files/public/.xyz%20app%204/Unt%2026__0.0_2.1"
        );
        for (var file : targetFiles) {
            response = send(HttpMethod.GET, "/v1/" + file, null, null, "authorization", "admin");
            verify(response, 200, "Test");
        }
    }

    @Test
    void testPublicationUpdate_WithoutResources() {
        // Create initial publication
        Response response = operationRequest("/v1/ops/publication/create", """
                {
                  "name": "Publication name",
                  "targetFolder": "public/folder/",
                  "rules": [
                    {
                      "source": "roles",
                      "function": "EQUAL",
                      "targets": ["user"]
                    }
                  ]
                }
                """);
        verify(response, 200);

        // Update as admin
        response = operationRequest("/v1/ops/publication/update", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "targetFolder": "public/folder/",
                  "rules": [
                    {
                       "source": "roles",
                        "function": "EQUAL",
                        "targets": ["manager"]
                    }
                  ]
                }
                """, "authorization", "admin");
        verify(response, 200);

        // list publications
        response = operationRequest("/v1/ops/publication/list", """
                {"url": "publications/public/"}
                """, "authorization", "admin");
        verifyJson(response, 200, """
                {
                   "publications" : [ {
                     "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                     "name" : "Publication name",
                     "targetFolder" : "public/folder/",
                     "status" : "PENDING",
                     "createdAt" : 0,
                     "resourceTypes" : [ ],
                     "author" : "EPM-RTC-GPT"
                   } ]
                 }
                """);

        // Approve the publication
        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verify(response, 200);
    }

    @Test
    void testPublicationUpdate() {
        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        // Create initial publication
        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket));
        verifyJson(response, 200, PUBLICATION_RESPONSE);

        // Try to update as non-admin user
        response = operationRequest("/v1/ops/publication/update", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/%s/my/folder/conversation",
                      "targetUrl": "conversations/public/folder/conversation2"
                    }
                  ]
                }
                """.formatted(bucket), "authorization", "user");
        verify(response, 403);

        // Try to update without resources
        response = operationRequest("/v1/ops/publication/update", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "name": "New name"
                }
                """, "authorization", "admin");
        verify(response, 400);

        // Update as admin
        response = operationRequest("/v1/ops/publication/update", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "targetFolder": "public/folder/",
                  "name": "New name",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/%s/my/folder/conversation",
                      "targetUrl": "conversations/public/folder/conversation2"
                    }
                  ]
                }
                """.formatted(bucket), "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "name": "Publication name",
                  "targetFolder" : "public/folder/",
                  "status" : "PENDING",
                  "createdAt" : 0,
                  "resources" : [ {
                    "action": "ADD",
                    "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                    "targetUrl" : "conversations/public/folder/conversation2",
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation2",
                    "publishCredentials" : false
                   } ],
                  "resourceTypes" : [ "CONVERSATION" ],
                  "author" : "EPM-RTC-GPT"
                }
                """);

        // list publications
        response = operationRequest("/v1/ops/publication/list", """
                {"url": "publications/public/"}
                """, "authorization", "admin");
        verifyJson(response, 200, """
                {
                   "publications" : [ {
                     "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                     "name" : "Publication name",
                     "targetFolder" : "public/folder/",
                     "status" : "PENDING",
                     "createdAt" : 0,
                     "resourceTypes" : [ "CONVERSATION" ],
                     "author" : "EPM-RTC-GPT"
                   } ]
                 }
                """);

        // Approve the publication
        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verify(response, 200);

        // Try to update approved publication
        response = operationRequest("/v1/ops/publication/update", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/%s/my/folder/conversation",
                      "targetUrl": "conversations/public/folder/conversation3"
                    }
                  ]
                }
                """.formatted(bucket), "authorization", "admin");
        verify(response, 400);
    }

    @Test
    void testPublicationUpdate_TargetFolderOnly() {
        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        // Create initial publication
        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket));
        verifyJson(response, 200, PUBLICATION_RESPONSE);

        // Update as admin
        response = operationRequest("/v1/ops/publication/update", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "targetFolder": "public/new-folder/",
                  "name": "New name",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/%s/my/folder/conversation",
                      "targetUrl": "conversations/public/new-folder/conversation2"
                    }
                  ]
                }
                """.formatted(bucket), "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "name": "Publication name",
                  "targetFolder" : "public/new-folder/",
                  "status" : "PENDING",
                  "createdAt" : 0,
                  "resources" : [ {
                    "action": "ADD",
                    "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                    "targetUrl" : "conversations/public/new-folder/conversation2",
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation2",
                    "publishCredentials" : false
                   } ],
                  "resourceTypes" : [ "CONVERSATION" ],
                  "author" : "EPM-RTC-GPT"
                }
                """);

        // list publications
        response = operationRequest("/v1/ops/publication/list", """
                {"url": "publications/public/"}
                """, "authorization", "admin");
        verifyJson(response, 200, """
                {
                   "publications" : [ {
                     "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                     "name" : "Publication name",
                     "targetFolder" : "public/new-folder/",
                     "status" : "PENDING",
                     "createdAt" : 0,
                     "resourceTypes" : [ "CONVERSATION" ],
                     "author" : "EPM-RTC-GPT"
                   } ]
                 }
                """);

        // Approve the publication
        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verify(response, 200);

        // Try to update approved publication
        response = operationRequest("/v1/ops/publication/update", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/%s/my/folder/conversation",
                      "targetUrl": "conversations/public/folder/conversation3"
                    }
                  ]
                }
                """.formatted(bucket), "authorization", "admin");
        verify(response, 400);
    }

    @Test
    void testPublicationUpdateAfterSourceIsRemoved() {
        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        // Create initial publication
        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket));
        verifyJson(response, 200, PUBLICATION_RESPONSE);

        response = resourceRequest(HttpMethod.DELETE, "/my/folder/conversation");
        verify(response, 200);

        // Update as admin
        response = operationRequest("/v1/ops/publication/update", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "targetFolder": "public/folder/",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/%s/my/folder/conversation",
                      "targetUrl": "conversations/public/folder/conversation2"
                    }
                  ]
                }
                """.formatted(bucket), "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "name": "Publication name",
                  "targetFolder" : "public/folder/",
                  "status" : "PENDING",
                  "createdAt" : 0,
                  "resources" : [ {
                    "action": "ADD",
                    "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                    "targetUrl" : "conversations/public/folder/conversation2",
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation2",
                    "publishCredentials" : false
                   } ],
                  "resourceTypes" : [ "CONVERSATION" ],
                  "author" : "EPM-RTC-GPT"
                }
                """);

        // Approve the publication
        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verify(response, 200);

        // Try to update approved publication
        response = operationRequest("/v1/ops/publication/update", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/%s/my/folder/conversation",
                      "targetUrl": "conversations/public/folder/conversation3"
                    }
                  ]
                }
                """.formatted(bucket), "authorization", "admin");
        verify(response, 400);
    }

    @Test
    void testUpdatePublication_ReplaceLinks() throws Exception {
        Response response = upload(HttpMethod.PUT, "/v1/files/" + bucket + "/file", null, "text data");
        verify(response, 200);
        String conversationTemplate = """
                {
                "id": "%s",
                "name": "display_name",
                "model": {"id": "model_id"},
                "prompt": "system prompt",
                "temperature": 1,
                "folderId": "%s",
                "messages": [{
                    "role": "user",
                    "content": "what's the file?",
                    "custom_content": {
                        "attachments": [
                          {
                            "type": "text/markdown",
                            "title": "title",
                            "url": "%s"
                          }
                        ]
                    }
                }],
                "assistantModelId": "assistantId",
                "lastActivityDate": 4848683153
                }
                """;
        JsonNode fileResponse = ProxyUtil.MAPPER.readTree(response.body());
        String conversation = conversationTemplate.formatted("conversation_id", "folder1", fileResponse.get("url").asText());

        response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation1", conversation);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST_WITH_FILE.formatted(bucket, "1", "1", "ADD_IF_ABSENT", bucket));
        verify(response, 200);

        // Update as admin
        response = operationRequest("/v1/ops/publication/update", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "name": "Publication name",
                  "targetFolder": "public/folder/",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/%s/my/folder/conversation%s",
                      "targetUrl": "conversations/public/folder/conversation%s"
                    },
                    {
                      "action": "ADD",
                      "sourceUrl": "files/%s/file",
                      "targetUrl": "files/public/folder/new_file"
                    }
                  ]
                }
                """.formatted(bucket, "1", "1", bucket), "authorization", "admin");
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verify(response, 200);

        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation1");
        verifyJsonNotExact(response, 200, conversationTemplate.formatted("conversations/public/folder/conversation1",
                "conversations/public/folder", "files/public/folder/new_file"));

    }

    @Test
    void testUpdatePublication_WhenNewResourceTypeAdded() throws Exception {
        Response response = upload(HttpMethod.PUT, "/v1/files/" + bucket + "/file", null, "text data");
        verify(response, 200);
        String conversationTemplate = """
                {
                "id": "%s",
                "name": "display_name",
                "model": {"id": "model_id"},
                "prompt": "system prompt",
                "temperature": 1,
                "folderId": "%s",
                "messages": [{
                    "role": "user",
                    "content": "what's the file?",
                    "custom_content": {
                        "attachments": [
                          {
                            "type": "text/markdown",
                            "title": "title",
                            "url": "%s"
                          }
                        ]
                    }
                }],
                "assistantModelId": "assistantId",
                "lastActivityDate": 4848683153
                }
                """;
        JsonNode fileResponse = ProxyUtil.MAPPER.readTree(response.body());
        String conversation = conversationTemplate.formatted("conversation_id", "folder1", fileResponse.get("url").asText());

        response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", conversation);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket));
        verify(response, 200);

        // Update as admin - bad source file
        response = operationRequest("/v1/ops/publication/update", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "name": "Publication name",
                  "targetFolder": "public/folder/",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/%s/my/folder/conversation%s",
                      "targetUrl": "conversations/public/folder/conversation%s"
                    },
                    {
                      "action": "ADD",
                      "sourceUrl": "files/%s/invalid_file",
                      "targetUrl": "files/public/folder/new_file"
                    }
                  ]
                }
                """.formatted(bucket, "", "1", bucket), "authorization", "admin");
        verify(response, 400);

        // Update as admin
        response = operationRequest("/v1/ops/publication/update", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "name": "Publication name",
                  "targetFolder": "public/folder/",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "conversations/%s/my/folder/conversation%s",
                      "targetUrl": "conversations/public/folder/conversation%s"
                    },
                    {
                      "action": "ADD",
                      "sourceUrl": "files/%s/file",
                      "targetUrl": "files/public/folder/new_file"
                    }
                  ]
                }
                """.formatted(bucket, "", "1", bucket), "authorization", "admin");
        verify(response, 200);

        // list publications
        response = operationRequest("/v1/ops/publication/list", """
                {"url": "publications/public/"}
                """, "authorization", "admin");
        verifyJsonNotExact(response, 200, """
                {
                   "publications" : [ {
                     "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                     "name" : "Publication name",
                     "targetFolder" : "public/folder/",
                     "status" : "PENDING",
                     "createdAt" : 0,
                     "resourceTypes" : [ "@ignore", "@ignore" ],
                     "author" : "EPM-RTC-GPT"
                   } ]
                 }
                """);
        Publications publications = ProxyUtil.convertToObject(response.body(), Publications.class);
        assertNotNull(publications);
        assertNotNull(publications.publications());
        assertFalse(publications.publications().isEmpty());
        assertEquals(publications.publications().iterator().next().getResourceTypes(), Set.of(ResourceTypes.FILE, ResourceTypes.CONVERSATION));

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verify(response, 200);

        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation1");
        verifyJsonNotExact(response, 200, conversationTemplate.formatted("conversations/public/folder/conversation1",
                "conversations/public/folder", "files/public/folder/new_file"));
    }

    @Test
    void testApplicationWithTypeSchemaPublish_Ok_FolderWithSubfolder_WhenUpdate() throws JsonProcessingException {

        List<String> filePaths =
                List.of("/v1/files/%s/xyz/test_file1.txt", "/v1/files/%s/xyz/test_file2.txt", "/v1/files/%s/xyz/abc/test_file1.txt", "/v1/files/%s/xyz/abc/test_file2.txt",
                        "/v1/files/%s/some/xyz/abc/test_file1.txt", "/v1/files/%s/some/xyz/abc/test_file2.txt", "/v1/files/%s/another/xyz");

        Response response;
        for (String filePath : filePaths) {
            response = upload(HttpMethod.PUT, filePath.formatted(bucket), null, "Test");
            Assertions.assertEquals(200, response.status());
        }

        response = send(HttpMethod.PUT, "/v1/applications/%s/test_app2".formatted(bucket), null, """
                  {
                      "displayName": "test_app2",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                      "applicationProperties": {
                        "property1": "test property1",
                        "property2": "test property2",
                        "property3": [
                                "files/%s/xyz/", "files/%s/some/xyz/", "files/%s/another/xyz"
                        ]
                       },
                       "userRoles": [
                            "Admin"
                       ],
                       "forwardAuthToken": true,
                       "iconUrl": "https://mydial.somewhere.com/app-icon.svg",
                       "description": "My application description"
                  }
                """.formatted(bucket, bucket, bucket));
        Assertions.assertEquals(200, response.status());

        response = operationRequest("/v1/ops/publication/create", """
                {
                      "name": "Publication of my application",
                      "targetFolder": "public/folder/",
                      "resources": [
                        {
                          "action": "ADD",
                          "sourceUrl": "applications/%s/test_app2",
                          "targetUrl": "applications/public/folder/with_apps/test_app2"
                        }
                      ],
                      "rules": [
                        {
                          "source": "roles",
                          "function": "TRUE"
                        }
                      ]
                    }
                """.formatted(bucket));
        String correctResponse = """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "name" : "Publication of my application",
                  "targetFolder" : "public/folder/",
                  "status" : "PENDING",
                  "createdAt" : 0,
                  "resources" : [ {
                            "action" : "ADD",
                            "sourceUrl" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app2",
                            "targetUrl" : "applications/public/folder/with_apps/test_app2",
                            "reviewUrl" : "applications/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/with_apps/test_app2",
                            "publishCredentials" : false
                          }
                  ],
                  "resourceTypes" : [ "APPLICATION" ],
                  "rules" : [ {
                    "function" : "TRUE",
                    "source" : "roles",
                    "targets" : null
                  } ],
                  "author" : "EPM-RTC-GPT"
                }""";


        verifyJsonNotExact(response, 200, correctResponse);

        // Update as admin
        response = operationRequest("/v1/ops/publication/update", """
                {
                "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                "name": "Publication of my application",
                 "targetFolder": "public/folder2/",
                 "resources" : [ {
                            "action" : "ADD",
                            "sourceUrl" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app2",
                            "targetUrl" : "applications/public/folder2/with_apps/test_app3"
                          }
                  ],
                      "rules": [
                        {
                          "source": "roles",
                          "function": "TRUE"
                        }
                      ]
                    }
                """, "authorization", "admin");
        verify(response, 200);
        JsonNode responseJson = ProxyUtil.MAPPER.readTree(response.body());
        JsonNode resources = responseJson.get("resources");

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verify(response, 200);

        String targetUrl = resources.get(0).get("targetUrl").asText();
        response = send(HttpMethod.GET, "/v1/" + targetUrl, null, null, "authorization", "admin");
        correctResponse = """
                {
                  "name" : "applications/public/folder2/with_apps/test_app3",
                  "display_name" : "test_app2",
                  "icon_url" : "https://mydial.somewhere.com/app-icon.svg",
                  "description" : "My application description",
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
                  "application_properties" : {
                    "property1" : "test property1",
                    "property2" : "test property2",
                    "property3" : [ "files/public/folder2/with_apps/.test_app3/xyz/",
                    "files/public/folder2/with_apps/.test_app3/xyz_2/",
                    "files/public/folder2/with_apps/.test_app3/xyz_3" ]
                  },
                  "application_type_schema_id" : "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                  "routes" : { }
                }""";
        verifyJsonNotExact(response, 200, correctResponse);

        response = send(HttpMethod.GET, "/v1/metadata/files/public/folder2/with_apps/.test_app3/", "recursive=true", null, "authorization", "admin");
        verify(response, 200);
        responseJson = ProxyUtil.MAPPER.readTree(response.body());
        List<String> files = new ArrayList<>();
        for (var item : responseJson.get("items")) {
            String url = item.get("url").textValue();
            if (!url.endsWith("/")) {
                files.add(url);
            }
        }
        assertEquals(7, files.size());
        List<String> appFiles = List.of("files/public/folder2/with_apps/.test_app3/xyz/",
                "files/public/folder2/with_apps/.test_app3/xyz_2/",
                "files/public/folder2/with_apps/.test_app3/xyz_3");
        int count = 0;
        for (var file : files) {
            for (var parent : appFiles) {
                if (file.startsWith(parent)) {
                    count++;
                    break;
                }
            }
        }
        assertEquals(count, files.size(), "Application files are missed");
    }

    @Test
    void testApplicationWithTypeSchemaPublish_WhenCopyAppBucket() throws IOException {

        var response = send(HttpMethod.PUT, "/v1/applications/%s/test_app2".formatted(bucket), null, """
                  {
                      "displayName": "test_app2",
                      "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_application_type",
                      "applicationProperties": {
                        "property1": "test property1",
                        "property2": "test property2"
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

        // simulate application's work
        // put file to app bucket
        String appFileFolder = testDir + "/test/test-2/Keys/applications/%s/test_app2/files/folder".formatted(bucket);
        List<String> lines = Arrays.asList("The first line", "The second line");
        Path path = Paths.get(appFileFolder + "/app_file1.txt");
        Files.createDirectories(Paths.get(appFileFolder));
        Files.write(path, lines, StandardCharsets.UTF_8);

        response = operationRequest("/v1/ops/publication/create", """
                {
                      "name": "Publication of my application",
                      "targetFolder": "public/folder/",
                      "resources": [
                        {
                          "action": "ADD",
                          "sourceUrl": "applications/%s/test_app2",
                          "targetUrl": "applications/public/folder/with_apps/test_app2"
                        }
                      ],
                      "rules": [
                        {
                          "source": "roles",
                          "function": "TRUE"
                        }
                      ]
                    }
                """.formatted(bucket));
        String correctResponse = """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "name" : "Publication of my application",
                  "targetFolder" : "public/folder/",
                  "status" : "PENDING",
                  "createdAt" : 0,
                  "resources" : [ {
                            "action" : "ADD",
                            "sourceUrl" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/test_app2",
                            "targetUrl" : "applications/public/folder/with_apps/test_app2",
                            "reviewUrl" : "applications/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/with_apps/test_app2",
                            "publishCredentials" : false
                          }
                  ],
                  "resourceTypes" : [ "APPLICATION" ],
                  "rules" : [ {
                    "function" : "TRUE",
                    "source" : "roles",
                    "targets" : null
                  } ],
                  "author" : "EPM-RTC-GPT"
                }""";

        verifyJsonNotExact(response, 200, correctResponse);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verify(response, 200);

        // check app files are copied to public app folder
        var appPublicFiles = new File(testDir + "/test/test-2/Keys/applications/public/folder/with_apps/test_app2/files/folder").listFiles();
        assertNotNull(appPublicFiles);
        assertEquals(1, appPublicFiles.length);
        assertEquals("app_file1.txt", appPublicFiles[0].getName());

    }

}

package com.epam.aidial.core;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

class PublicationApiTest extends ResourceBaseTest {

    private static final String PUBLICATION_REQUEST = """
            {
              "url": "publications/%s/",
              "targetUrl": "public/folder/",
              "resources": [
                {
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

    private static final String PUBLICATION_URL = """
            {
              "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123"
            }
            """;

    private static final String PUBLICATION_RESPONSE = """
            {
              "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
              "targetUrl" : "public/folder/",
              "status" : "PENDING",
              "createdAt" : 0,
              "resources" : [ {
                "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                "targetUrl" : "conversations/public/folder/conversation",
                "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation"
               } ],
              "rules" : [ {
                "function" : "EQUAL",
                "source" : "roles",
                "targets" : [ "user" ]
              } ]
            }
            """;

    @Test
    void testPublicationCreation() {
        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket, bucket));
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
                    "targetUrl" : "public/folder/",
                    "status" : "PENDING",
                    "createdAt" : 0
                  } ]
                }
                """);


        response = send(HttpMethod.GET, "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verifyJson(response, 200, CONVERSATION_BODY_1);

        response = send(HttpMethod.PUT, "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verify(response, 403);

        response = send(HttpMethod.DELETE, "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verify(response, 403);


        response = send(HttpMethod.GET, "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation",
                null, null, "authorization", "user");
        verify(response, 403);


        response = send(HttpMethod.GET, "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation",
                null, null, "authorization", "admin");
        verify(response, 200);
    }

    @Test
    void testPublicationDeletion() {
        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket, bucket));
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
    void testDeleteApprovedPublicationWorkflow() {
        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket, bucket));
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "targetUrl" : "public/folder/",
                  "status" : "APPROVED",
                  "createdAt" : 0,
                  "resources" : [ {
                    "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                    "targetUrl" : "conversations/public/folder/conversation",
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation"
                   } ],
                   "rules" : [ {
                     "function" : "EQUAL",
                     "source" : "roles",
                     "targets" : [ "user" ]
                   } ]
                  
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
                    "targetUrl":"public/folder/",
                    "status":"APPROVED",
                    "createdAt":0
                    }]
                }
                """);

        // initialize delete request by user (publication owner)
        response = operationRequest("/v1/ops/publication/delete", PUBLICATION_URL);
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
                    "targetUrl":"public/folder/",
                    "status":"REQUESTED_FOR_DELETION",
                    "createdAt":0
                    }]
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
                    "url":"publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                    "targetUrl":"public/folder/",
                    "status":"REQUESTED_FOR_DELETION",
                    "createdAt":0
                    }]
                }
                """);

        // delete publication by admin
        response = operationRequest("/v1/ops/publication/delete", PUBLICATION_URL, "authorization", "admin");
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
    }

    @Test
    void testRejectUserDeletionRequestWorkflow() {
        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket, bucket));
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "targetUrl" : "public/folder/",
                  "status" : "APPROVED",
                  "createdAt" : 0,
                  "resources" : [ {
                    "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                    "targetUrl" : "conversations/public/folder/conversation",
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation"
                   } ],
                   "rules" : [ {
                     "function" : "EQUAL",
                     "source" : "roles",
                     "targets" : [ "user" ]
                   } ]
                  
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
                    "targetUrl":"public/folder/",
                    "status":"APPROVED",
                    "createdAt":0
                    }]
                }
                """);

        // initialize delete request by user (publication owner)
        response = operationRequest("/v1/ops/publication/delete", PUBLICATION_URL);
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
                    "targetUrl":"public/folder/",
                    "status":"REQUESTED_FOR_DELETION",
                    "createdAt":0
                    }]
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
                    "url":"publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                    "targetUrl":"public/folder/",
                    "status":"REQUESTED_FOR_DELETION",
                    "createdAt":0
                    }]
                }
                """);

        // reject deletion request by admin
        response = operationRequest("/v1/ops/publication/reject", PUBLICATION_URL, "authorization", "admin");
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

        // verify publication rolled back to status APPROVED
        response = operationRequest("/v1/ops/publication/list", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/"
                }
                """);
        verifyJson(response, 200, """
                {
                  "publications": [{
                    "url":"publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                    "targetUrl":"public/folder/",
                    "status":"APPROVED",
                    "createdAt":0
                    }]
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
    void testPublicationApprove() {
        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket, bucket));
        verify(response, 200);


        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL);
        verify(response, 403);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "user");
        verify(response, 403);

        response = operationRequest("/v1/ops/publication/approve", PUBLICATION_URL, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "targetUrl" : "public/folder/",
                  "status" : "APPROVED",
                  "createdAt" : 0,
                  "resources" : [ {
                    "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                    "targetUrl" : "conversations/public/folder/conversation",
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation"
                   } ],
                   "rules" : [ {
                     "function" : "EQUAL",
                     "source" : "roles",
                     "targets" : [ "user" ]
                   } ]
                  
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
        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket, bucket));
        verify(response, 200);


        response = operationRequest("/v1/ops/publication/reject", PUBLICATION_URL);
        verify(response, 403);

        response = operationRequest("/v1/ops/publication/reject", PUBLICATION_URL, "authorization", "user");
        verify(response, 403);

        response = operationRequest("/v1/ops/publication/reject", PUBLICATION_URL, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "targetUrl" : "public/folder/",
                  "status" : "REJECTED",
                  "createdAt" : 0,
                  "resources" : [ {
                    "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                    "targetUrl" : "conversations/public/folder/conversation",
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation"
                  } ],
                  "rules" : [ {
                    "function" : "EQUAL",
                    "source" : "roles",
                    "targets" : [ "user" ]
                  } ]
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
        Response response = send(HttpMethod.GET, "/v1/metadata/conversations/public/", null, null,
                "authorization", "user");
        verifyJson(response, 200, """
                {
                  "name" : null,
                  "parentPath" : null,
                  "bucket" : "public",
                  "url" : "conversations/public/",
                  "nodeType" : "FOLDER",
                  "resourceType" : "CONVERSATION",
                  "items" : [ ]
                }
                """);

        response = send(HttpMethod.GET, "/v1/metadata/conversations/public/", null, null,
                "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "name" : null,
                  "parentPath" : null,
                  "bucket" : "public",
                  "url" : "conversations/public/",
                  "nodeType" : "FOLDER",
                  "resourceType" : "CONVERSATION",
                  "items" : [ ]
                }
                """);


        response = resourceRequest(HttpMethod.PUT, "/my/folder1/conversation1", CONVERSATION_BODY_1);
        verify(response, 200);

        response = resourceRequest(HttpMethod.PUT, "/my/folder2/conversation2", CONVERSATION_BODY_2);
        verify(response, 200);


        response = operationRequest("/v1/ops/publication/create", """
                {
                  "url": "publications/%s/",
                  "targetUrl": "public/folder1/",
                  "resources": [
                    {
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
                """.formatted(bucket, bucket));
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/approve", """
                {"url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123"}
                """, "authorization", "admin");
        verify(response, 200);


        response = operationRequest("/v1/ops/publication/create", """
                {
                  "url": "publications/%s/",
                  "targetUrl": "public/folder2/",
                  "resources": [
                    {
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
                """.formatted(bucket, bucket));
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/approve", """
                {"url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124"}
                """, "authorization", "admin");
        verify(response, 200);


        response = send(HttpMethod.GET, "/v1/metadata/conversations/public/", null, null,
                "authorization", "user");
        verifyJson(response, 200, """
                {
                   "name" : null,
                   "parentPath" : null,
                   "bucket" : "public",
                   "url" : "conversations/public/",
                   "nodeType" : "FOLDER",
                   "resourceType" : "CONVERSATION",
                   "items" : [
                   {
                     "name" : "folder1",
                     "parentPath" : null,
                     "bucket" : "public",
                     "url" : "conversations/public/folder1/",
                     "nodeType" : "FOLDER",
                     "resourceType" : "CONVERSATION",
                     "items" : null
                   } ]
                 }
                """);

        response = send(HttpMethod.GET, "/v1/metadata/conversations/public/", "recursive=true", null,
                "authorization", "user");
        verifyJsonNotExact(response, 200, """
                {
                  "name" : null,
                  "parentPath" : null,
                  "bucket" : "public",
                  "url" : "conversations/public/",
                  "nodeType" : "FOLDER",
                  "resourceType" : "CONVERSATION",
                  "items" : [ {
                    "name" : "conversation1",
                    "parentPath" : "folder1",
                    "bucket" : "public",
                    "url" : "conversations/public/folder1/conversation1",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
                    "updatedAt" : "@ignore"
                  } ]
                }
                """);

        response = send(HttpMethod.GET, "/v1/metadata/conversations/public/", null, null,
                "authorization", "admin");
        verifyJsonNotExact(response, 200, """
                {
                   "name" : null,
                   "parentPath" : null,
                   "bucket" : "public",
                   "url" : "conversations/public/",
                   "nodeType" : "FOLDER",
                   "resourceType" : "CONVERSATION",
                   "items" : [ {
                     "name" : "folder1",
                     "parentPath" : null,
                     "bucket" : "public",
                     "url" : "conversations/public/folder1/",
                     "nodeType" : "FOLDER",
                     "resourceType" : "CONVERSATION",
                     "items" : null
                   }, {
                     "name" : "folder2",
                     "parentPath" : null,
                     "bucket" : "public",
                     "url" : "conversations/public/folder2/",
                     "nodeType" : "FOLDER",
                     "resourceType" : "CONVERSATION",
                     "items" : null
                   } ]
                 }
                """);

        response = send(HttpMethod.GET, "/v1/metadata/conversations/public/", "recursive=true", null,
                "authorization", "admin");
        verifyJsonNotExact(response, 200, """
                {
                  "name" : null,
                  "parentPath" : null,
                  "bucket" : "public",
                  "url" : "conversations/public/",
                  "nodeType" : "FOLDER",
                  "resourceType" : "CONVERSATION",
                  "items" : [ {
                    "name" : "conversation1",
                    "parentPath" : "folder1",
                    "bucket" : "public",
                    "url" : "conversations/public/folder1/conversation1",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
                    "updatedAt" : "@ignore"
                  }, {
                    "name" : "conversation2",
                    "parentPath" : "folder2",
                    "bucket" : "public",
                    "url" : "conversations/public/folder2/conversation2",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
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

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket, bucket));
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
                    "targetUrl" : "public/folder/",
                    "status" : "PENDING",
                    "createdAt" : 0
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

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket, bucket));
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
        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket, bucket));
        verify(response, 200);

        // verify admin can view publication request
        response = operationRequest("/v1/ops/publication/list", """
                {"url": "publications/public/"}
                """, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "publications" : [ {
                    "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                    "targetUrl" : "public/folder/",
                    "status" : "PENDING",
                    "createdAt" : 0
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
}
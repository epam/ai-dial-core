package com.epam.aidial.core;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

class PublicationApiTest extends ResourceBaseTest {

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
                "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation"
               } ],
              "resourceTypes" : [ "CONVERSATION" ],
              "rules" : [ {
                "function" : "EQUAL",
                "source" : "roles",
                "targets" : [ "user" ]
              } ],
              "etag" : "5d771f7a1ad48287b9484111aa4604de"
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
                    "etag" : "5d771f7a1ad48287b9484111aa4604de"
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
                    "selectedAddons": ["R", "T", "G"],
                    "assistantModelId": "assistantId",
                    "lastActivityDate": 4848683153
                }
                """);

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
    void testDeleteApprovedPublicationWorkflow() {
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
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation"
                   } ],
                   "resourceTypes" : [ "CONVERSATION" ],
                   "rules" : [],
                   "etag" : "5763d30a2ba16c5432908b6f4a7c6388"
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
                    "etag" : "5763d30a2ba16c5432908b6f4a7c6388"
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
                        "etag" : "5763d30a2ba16c5432908b6f4a7c6388"
                    },
                    {
                        "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124",
                         "targetFolder":"public/folder/",
                        "status" : "PENDING",
                        "createdAt" : 0,
                        "resourceTypes" : [ "CONVERSATION" ],
                        "etag" : "f72b4fcbb9906e93525fbe726064ee24"
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
                    "etag" : "f72b4fcbb9906e93525fbe726064ee24"
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
                        "etag" : "5763d30a2ba16c5432908b6f4a7c6388"
                    },
                    {
                        "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124",
                        "targetFolder":"public/folder/",
                        "status":"APPROVED",
                        "createdAt" : 0,
                        "resourceTypes" : [ "CONVERSATION" ],
                        "etag" : "5f6cb46289c1474b2e8dc81446bc61fd"
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
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation"
                   } ],
                   "resourceTypes" : [ "CONVERSATION" ],
                   "rules" : [],
                   "etag": "5763d30a2ba16c5432908b6f4a7c6388"
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
                    "etag" : "5763d30a2ba16c5432908b6f4a7c6388"
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
                        "etag" : "5763d30a2ba16c5432908b6f4a7c6388"
                    },
                    {
                        "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124",
                        "targetFolder":"public/folder/",
                        "status" : "PENDING",
                        "createdAt" : 0,
                        "resourceTypes" : [ "CONVERSATION" ],
                        "etag" : "0c28b12696234148ac7179067bc1e3d9"
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
                    "etag" : "0c28b12696234148ac7179067bc1e3d9"
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
                        "etag" : "5763d30a2ba16c5432908b6f4a7c6388"
                    },
                    {
                        "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0124",
                        "targetFolder" : "public/folder/",
                        "status" : "REJECTED",
                        "createdAt" : 0,
                        "resourceTypes" : [ "CONVERSATION" ],
                        "etag" : "87f18a6d9971dbc55173629b6ad7d43c"
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
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation"
                   } ],
                   "resourceTypes" : [ "CONVERSATION" ],
                   "rules" : [ {
                     "function" : "EQUAL",
                     "source" : "roles",
                     "targets" : [ "user" ]
                   } ],
                   "etag" : "70ad8bb6114833196dd4a35bb705134d"
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

        response = operationRequest("/v1/ops/publication/create", PUBLICATION_REQUEST.formatted(bucket));
        verify(response, 200);


        response = operationRequest("/v1/ops/publication/reject", PUBLICATION_URL);
        verify(response, 403);

        response = operationRequest("/v1/ops/publication/reject", PUBLICATION_URL, "authorization", "user");
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
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation"
                  } ],
                  "resourceTypes" : [ "CONVERSATION" ],
                  "rules" : [ {
                    "function" : "EQUAL",
                    "source" : "roles",
                    "targets" : [ "user" ]
                  } ],
                  "etag" : "b3797c3be91c9d2fabe299931ec3b8d5"
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
                  "permissions" : [ "READ", "WRITE" ],
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
                  "items" : [ {
                    "name" : "conversation1",
                    "parentPath" : "folder1",
                    "bucket" : "public",
                    "url" : "conversations/public/folder1/conversation1",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
                    "updatedAt" : "@ignore",
                    "permissions" : [ "READ" ]
                  } ]
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
                  "permissions" : [ "READ", "WRITE" ],
                   "items" : [ {
                     "name" : "folder1",
                     "parentPath" : null,
                     "bucket" : "public",
                     "url" : "conversations/public/folder1/",
                     "nodeType" : "FOLDER",
                     "resourceType" : "CONVERSATION",
                     "permissions" : [ "READ", "WRITE" ],
                     "items" : null
                   }, {
                     "name" : "folder2",
                     "parentPath" : null,
                     "bucket" : "public",
                     "url" : "conversations/public/folder2/",
                     "nodeType" : "FOLDER",
                     "resourceType" : "CONVERSATION",
                     "permissions" : [ "READ", "WRITE" ],
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
                  "permissions" : [ "READ", "WRITE" ],
                  "items" : [ {
                    "name" : "conversation1",
                    "parentPath" : "folder1",
                    "bucket" : "public",
                    "url" : "conversations/public/folder1/conversation1",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
                    "updatedAt" : "@ignore",
                    "permissions" : [ "READ", "WRITE" ]
                  }, {
                    "name" : "conversation2",
                    "parentPath" : "folder2",
                    "bucket" : "public",
                    "url" : "conversations/public/folder2/conversation2",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
                    "updatedAt" : "@ignore",
                    "permissions" : [ "READ", "WRITE" ]
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
                    "etag" : "5d771f7a1ad48287b9484111aa4604de"
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
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation"
                   } ],
                  "resourceTypes" : [ "CONVERSATION" ],
                  "rules" : [ {
                    "function" : "CONTAIN",
                    "source" : "title",
                    "targets" : [ "Engineer" ]
                  } ],
                  "etag" : "346dad4502971e74ab367c3d75e73250"
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
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation"
                   } ],
                   "resourceTypes" : [ "CONVERSATION" ],
                   "rules" : [ {
                    "function" : "CONTAIN",
                    "source" : "title",
                    "targets" : [ "Engineer" ]
                   } ],
                   "etag" : "3abd6c5c623fcda91c1bd0c108565599"
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
                    "etag" : "5d771f7a1ad48287b9484111aa4604de"
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
                  "etag" : "4f8bedf82cb3ec5fa2ca1492ce4c64ef"
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
}
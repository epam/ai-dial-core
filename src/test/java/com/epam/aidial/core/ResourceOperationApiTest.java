package com.epam.aidial.core;

import com.epam.aidial.core.data.InvitationLink;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ResourceOperationApiTest extends ResourceBaseTest {

    @Test
    void testMoveResourceWorkflow() {
        // upload resource
        Response response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        // verify resource can be downloaded
        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verifyJson(response, 200, CONVERSATION_BODY_1);

        // verify move operation
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "destinationUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2"
                }
                """);
        verify(response, 200);

        // verify old resource deleted
        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verify(response, 404);

        // verify new resource can be downloaded
        response = resourceRequest(HttpMethod.GET, "/folder2/conversation2");
        verifyJson(response, 200, CONVERSATION_BODY_1);
    }

    @Test
    void testMoveResourceWorkflowWhenDestinationResourceExists() {
        // upload resource
        Response response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        response = resourceRequest(HttpMethod.PUT, "/folder2/conversation2", CONVERSATION_BODY_2);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2\"");

        // verify resource can be downloaded
        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verifyJson(response, 200, CONVERSATION_BODY_1);

        response = resourceRequest(HttpMethod.GET, "/folder2/conversation2");
        verifyJson(response, 200, CONVERSATION_BODY_2);

        // verify move operation
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "destinationUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2"
                }
                """);
        verify(response, 400);

        // verify resource can be downloaded
        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verifyJson(response, 200, CONVERSATION_BODY_1);

        response = resourceRequest(HttpMethod.GET, "/folder2/conversation2");
        verifyJson(response, 200, CONVERSATION_BODY_2);

        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "destinationUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2",
                   "overwrite": true
                }
                """);
        verify(response, 200);

        // verify old resource deleted
        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verify(response, 404);

        // verify new resource can be downloaded
        response = resourceRequest(HttpMethod.GET, "/folder2/conversation2");
        verifyJson(response, 200, CONVERSATION_BODY_1);
    }

    @Test
    void testMoveOperationCopySharedAccess() {
        Response response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        // initialize share request
        response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation"
                    }
                  ]
                }
                """);
        verify(response, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(response.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        // verify invitation details
        response = send(HttpMethod.GET, invitationLink.invitationLink(), null, null);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        // verify user2 do not have access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation", null, "Api-key", "proxyKey2");
        verify(response, 403);

        // accept invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2");
        verify(response, 200);

        // verify user2 has access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_1);

        // verify move operation
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "destinationUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2"
                }
                """);
        verify(response, 200);

        // verify user2 has access to the moved conversation
        response = resourceRequest(HttpMethod.GET, "/folder2/conversation2", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_1);

        // verify invitation contains moved conversation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), null, null);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2\"");

        // verify user1 has no shared_with_me resources
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "me"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources": []
                }
                """);

        // verify user2 has shared_with_me resource
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "me"
                }
                """, "Api-key", "proxyKey2");
        verifyJson(response, 200, """
                {
                  "resources" : [ {
                    "name" : "conversation2",
                    "parentPath" : "folder2",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
                    "permissions" : [ "READ" ]
                    } ]
                }
                """);

        // verify user1 has shared_by_me resource
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources" : [ {
                    "name" : "conversation2",
                    "parentPath" : "folder2",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
                    "permissions" : [ "READ" ]
                    } ]
                }
                """);

        // verify user2 has no shared_by_me resources
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """, "Api-key", "proxyKey2");
        verifyJson(response, 200, """
                {
                  "resources": []
                }
                """);
    }

    @Test
    void testMoveOperationErrors() {
        // verify sourceUrl must be present
        Response response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "destinationUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2"
                }
                """);
        verify(response, 400);

        // verify destinationUrl must be present
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation"
                }
                """);
        verify(response, 400);

        // verify source and dest must be the same type
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "destinationUrl": "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2"
                }
                """);
        verify(response, 400);

        // verify source must belong to the user
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder/conversation",
                   "destinationUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2"
                }
                """);
        verify(response, 400);

        // verify move do not support folders
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/",
                   "destinationUrl": "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2"
                }
                """);
        verify(response, 400);

        // verify sourceUrl do not exists
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "destinationUrl": "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2"
                }
                """);
        verify(response, 400);
    }
}

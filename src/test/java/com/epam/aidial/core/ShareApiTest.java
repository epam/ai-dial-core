package com.epam.aidial.core;

import com.epam.aidial.core.data.InvitationLink;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ShareApiTest extends ResourceBaseTest {

    @Test
    public void testShareWorkflow() {
        // check no conversations shared with me
        Response response = operationRequest("/v1/ops/resource/share/list", """
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

        // check no conversations shared by me
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources": []
                }
                """);

        // create conversation
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation%201%40", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%201@\"");

        // initialize share request
        response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%201%40"
                    }
                  ]
                }
                """);
        verify(response, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(response.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        // verify invitation details
        response = send(HttpMethod.GET, invitationLink.invitationLink(), null, null);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%201@\"");

        // verify user2 do not have access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation%201%40", null, "Api-key", "proxyKey2");
        verify(response, 403);

        // accept invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2");
        verify(response, 200);

        // verify user2 has access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation%201%40", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_1);

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
                    "name" : "conversation 1@",
                    "parentPath" : "folder",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%201@",
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
                    "name" : "conversation 1@",
                    "parentPath" : "folder",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%201@",
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
    public void testShareForWrite() {
        // check no conversations shared with me
        Response response = operationRequest("/v1/ops/resource/share/list", """
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

        // check no conversations shared by me
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources": []
                }
                """);

        // create conversation
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation%201%40", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%201@\"");

        // initialize share request
        response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%201%40",
                      "permissions": [ "WRITE" ]
                    }
                  ]
                }
                """);
        verify(response, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(response.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        // verify invitation details
        response = send(HttpMethod.GET, invitationLink.invitationLink(), null, null);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%201@\"");

        // verify user2 cannot update the conversation
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation%201%40", CONVERSATION_BODY_2, "Api-key", "proxyKey2");
        verify(response, 403);

        // accept invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2");
        verify(response, 200);

        // verify user2 cannot read the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation%201%40", null, "Api-key", "proxyKey2");
        verify(response, 403);

        // verify user2 can now update the conversation
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation%201%40", CONVERSATION_BODY_2, "Api-key", "proxyKey2");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%201@\"");

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
                    "name" : "conversation 1@",
                    "parentPath" : "folder",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%201@",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
                    "permissions" : [ "WRITE" ]
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
                    "name" : "conversation 1@",
                    "parentPath" : "folder",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%201@",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
                    "permissions" : [ "WRITE" ]
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
    public void testRevokeSharedAccess() {
        // check no conversations shared with me
        Response response = operationRequest("/v1/ops/resource/share/list", """
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

        // check no conversations shared by me
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources": []
                }
                """);

        // create conversation
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation@", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation@\"");

        // initialize share request
        response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation@"
                    }
                  ]
                }
                """);
        verify(response, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(response.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        response = send(HttpMethod.GET, "/v1/invitations");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation@\"");
        verifyNotExact(response, 200, "\"permissions\":[\"READ\"]");

        // verify user2 do not have access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation@", null, "Api-key", "proxyKey2");
        verify(response, 403);

        // accept invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2");
        verify(response, 200);

        // verify user2 has access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation@", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_1);

        // revoke share access
        response = operationRequest("/v1/ops/resource/share/revoke", """
                {
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%40"
                    }
                  ]
                }
                """);
        verify(response, 200);

        response = send(HttpMethod.GET, "/v1/invitations");
        verifyJson(response, 200, """
                {
                  "invitations" : [ ]
                }
                """);

        // verify user2 do not have access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation@", null, "Api-key", "proxyKey2");
        verify(response, 403);

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

        // verify user2 has no shared_with_me resource
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "me"
                }
                """, "Api-key", "proxyKey2");
        verifyJson(response, 200, """
                {
                  "resources" : []
                }
                """);

        // verify user1 has no shared_by_me resource
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources" : []
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
    public void testPartiallyRevokeSharedAccess() {
        // check no conversations shared with me
        Response response = operationRequest("/v1/ops/resource/share/list", """
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

        // check no conversations shared by me
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources": []
                }
                """);

        // create conversation
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation@", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation@\"");

        // initialize share request
        response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation@",
                      "permissions": [ "READ", "WRITE" ]
                    }
                  ]
                }
                """);
        verify(response, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(response.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        response = send(HttpMethod.GET, "/v1/invitations");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation@\"");

        // verify user2 do not have access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation@", null, "Api-key", "proxyKey2");
        verify(response, 403);

        // verify user2 cannot update the conversation
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation@", CONVERSATION_BODY_2, "Api-key", "proxyKey2");
        verify(response, 403);

        // accept invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2");
        verify(response, 200);

        // verify user2 has access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation@", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_1);

        // verify user2 can update the conversation
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation@", CONVERSATION_BODY_2, "Api-key", "proxyKey2");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation@\"");

        // revoke share access
        response = operationRequest("/v1/ops/resource/share/revoke", """
                {
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%40",
                      "permissions": [ "WRITE" ]
                    }
                  ]
                }
                """);
        verify(response, 200);

        response = send(HttpMethod.GET, "/v1/invitations");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation@\"");
        verifyNotExact(response, 200, "\"permissions\":[\"READ\"]");

        // verify user2 still has access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation@", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_2);

        // verify user2 no longer able to update the conversation
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation@", CONVERSATION_BODY_1, "Api-key", "proxyKey2");
        verify(response, 403);

        // verify user2 still has shared_with_me resource
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "me"
                }
                """, "Api-key", "proxyKey2");
        verifyJson(response, 200, """
                {
                  "resources": [
                    {
                      "name": "conversation@",
                      "parentPath": "folder",
                      "bucket": "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation@",
                      "nodeType": "ITEM",
                      "resourceType": "CONVERSATION",
                      "permissions": [ "READ" ]
                    }
                  ]
                }
                """);

        // verify user1 still has shared_by_me resource
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources": [
                    {
                      "name": "conversation@",
                      "parentPath": "folder",
                      "bucket": "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation@",
                      "nodeType": "ITEM",
                      "resourceType": "CONVERSATION",
                      "permissions": [ "READ" ]
                    }
                  ]
                }
                """);
    }

    @Test
    public void testPartiallyRevokeSharedResources() {
        // check no conversations shared with me
        Response response = operationRequest("/v1/ops/resource/share/list", """
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

        // check no conversations shared by me
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources": []
                }
                """);

        // create conversation
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation@", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation@\"");

        // create conversation 2
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation2@", CONVERSATION_BODY_2);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2@\"");

        // initialize share request
        response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation@"
                    },
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2@"
                    }
                  ]
                }
                """);
        verify(response, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(response.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        response = send(HttpMethod.GET, "/v1/invitations");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation@\"");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2@\"");

        // verify user2 do not have access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation@", null, "Api-key", "proxyKey2");
        verify(response, 403);

        // verify user2 do not have access to the conversation 2
        response = resourceRequest(HttpMethod.GET, "/folder/conversation2@", null, "Api-key", "proxyKey2");
        verify(response, 403);

        // accept invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2");
        verify(response, 200);

        // verify user2 has access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation@", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_1);

        // verify user2 has access to the conversation 2
        response = resourceRequest(HttpMethod.GET, "/folder/conversation2@", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_2);

        // revoke share access
        response = operationRequest("/v1/ops/resource/share/revoke", """
                {
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%40"
                    }
                  ]
                }
                """);
        verify(response, 200);

        response = send(HttpMethod.GET, "/v1/invitations");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2@\"");

        // verify user2 no longer has access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation@", null, "Api-key", "proxyKey2");
        verify(response, 403);

        // verify user2 still has access to the conversation 2
        response = resourceRequest(HttpMethod.GET, "/folder/conversation2@", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_2);

        // verify user2 still has shared_with_me resource
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "me"
                }
                """, "Api-key", "proxyKey2");
        verifyJson(response, 200, """
                {
                  "resources": [
                    {
                      "name": "conversation2@",
                      "parentPath": "folder",
                      "bucket": "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2@",
                      "nodeType": "ITEM",
                      "resourceType": "CONVERSATION",
                      "permissions": [ "READ" ]
                    }
                  ]
                }
                """);

        // verify user1 still has shared_by_me resource
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources": [
                    {
                      "name": "conversation2@",
                      "parentPath": "folder",
                      "bucket": "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2@",
                      "nodeType": "ITEM",
                      "resourceType": "CONVERSATION",
                      "permissions": [ "READ" ]
                    }
                  ]
                }
                """);
    }

    @Test
    public void testRevokeOfNonSharedResource() {
        // create conversation
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

        // verify user2 do not have access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation", null, "Api-key", "proxyKey2");
        verify(response, 403);

        // accept invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2");
        verify(response, 200);

        // verify user2 has access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_1);

        // revoke share access of another resource that wasn't shared
        response = operationRequest("/v1/ops/resource/share/revoke", """
                {
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2"
                    }
                  ]
                }
                """);
        verify(response, 200);
    }

    @Test
    public void testRevokeOfNonSharedResource2() {
        // revoke share access of another resource that wasn't shared
        Response response = operationRequest("/v1/ops/resource/share/revoke", """
                {
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2"
                    }
                  ]
                }
                """);
        verify(response, 200);
    }

    @Test
    public void testDiscardSharedAccess() {
        // check no conversations shared with me
        Response response = operationRequest("/v1/ops/resource/share/list", """
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

        // check no conversations shared by me
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources": []
                }
                """);

        // create conversation
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation@", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation@\"");

        // initialize share request
        response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%40"
                    }
                  ]
                }
                """);
        verify(response, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(response.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        // verify user2 do not have access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation@", null, "Api-key", "proxyKey2");
        verify(response, 403);

        // accept invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2");
        verify(response, 200);

        // verify user2 has access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation%40", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_1);

        // discard share access
        response = operationRequest("/v1/ops/resource/share/discard", """
                {
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%40"
                    }
                  ]
                }
                """, "Api-key", "proxyKey2");
        verify(response, 200);

        // verify user2 do not have access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation%40", null, "Api-key", "proxyKey2");
        verify(response, 403);

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

        // verify user2 has no shared_with_me resource
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "me"
                }
                """, "Api-key", "proxyKey2");
        verifyJson(response, 200, """
                {
                  "resources" : []
                }
                """);

        // verify user1 has no shared_by_me resource
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources" : []
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
    public void testDiscardSharedFolderAccess() {
        // check no conversations shared with me
        Response response = operationRequest("/v1/ops/resource/share/list", """
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

        // check no conversations shared by me
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources": []
                }
                """);

        // create conversation1
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation1", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation1\"");

        // create conversation2
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation2", CONVERSATION_BODY_2);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2\"");

        // initialize share request
        response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/"
                    }
                  ]
                }
                """);
        verify(response, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(response.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        // verify user2 do not have access to the conversation1
        response = resourceRequest(HttpMethod.GET, "/folder/conversation1", null, "Api-key", "proxyKey2");
        verify(response, 403);

        // verify user2 do not have access to the conversation2
        response = resourceRequest(HttpMethod.GET, "/folder/conversation2", null, "Api-key", "proxyKey2");
        verify(response, 403);

        // accept invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2");
        verify(response, 200);

        // verify user2 has access to the conversation1
        response = resourceRequest(HttpMethod.GET, "/folder/conversation1", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_1);

        // verify user2 has access to the conversation2
        response = resourceRequest(HttpMethod.GET, "/folder/conversation2", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_2);

        // discard share access
        response = operationRequest("/v1/ops/resource/share/discard", """
                {
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/"
                    }
                  ]
                }
                """, "Api-key", "proxyKey2");
        verify(response, 200);

        // verify user2 do not have access to the conversation1
        response = resourceRequest(HttpMethod.GET, "/folder/conversation1", null, "Api-key", "proxyKey2");
        verify(response, 403);

        // verify user2 do not have access to the conversation2
        response = resourceRequest(HttpMethod.GET, "/folder/conversation2", null, "Api-key", "proxyKey2");
        verify(response, 403);

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

        // verify user2 has no shared_with_me resource
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "me"
                }
                """, "Api-key", "proxyKey2");
        verifyJson(response, 200, """
                {
                  "resources" : []
                }
                """);

        // verify user1 has no shared_by_me resource
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources" : []
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
    public void testDiscardNonSharedResource() {
        // create conversation
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

        // verify user2 do not have access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation", null, "Api-key", "proxyKey2");
        verify(response, 403);

        // accept invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2");
        verify(response, 200);

        // verify user2 has access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_1);

        // discard share access of another resource that wasn't shared
        response = operationRequest("/v1/ops/resource/share/discard", """
                {
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2"
                    }
                  ]
                }
                """, "Api-key", "proxyKey2");
        verify(response, 200);
    }

    @Test
    public void testDiscardNonSharedResource2() {
        // discard share access of another resource that wasn't shared
        Response response = operationRequest("/v1/ops/resource/share/discard", """
                {
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2"
                    }
                  ]
                }
                """, "Api-key", "proxyKey2");
        verify(response, 200);
    }

    @Test
    public void testCleanUpShareAccessWhenOnResourceDeletion() {
        // create conversation
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

        // verify user2 do not have access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation", null, "Api-key", "proxyKey2");
        verify(response, 403);

        // accept invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2");
        verify(response, 200);

        // verify user2 has access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_1);

        // delete resource
        response = resourceRequest(HttpMethod.DELETE, "/folder/conversation", null);
        verify(response, 200);

        // create resource with same name
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_2);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        // verify user2 has no access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation", null, "Api-key", "proxyKey2");
        verify(response, 403);
    }

    @Test
    public void testResourceDeletionWithoutSharingState() {
        // create conversation
        Response response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        // delete resource
        response = resourceRequest(HttpMethod.DELETE, "/folder/conversation", null);
        verify(response, 200);

        // create resource with same name
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_2);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        // download resource
        response = resourceRequest(HttpMethod.GET, "/folder/conversation", null);
        verify(response, 200, CONVERSATION_BODY_2);
    }

    @Test
    public void testShareRequestWithIncorrectBody() {
        Response response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationTypes": "link",
                  "resources": [
                     "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%201"
                  ]
                }
                """);
        verify(response, 400, "Can't initiate share request. Incorrect body");
    }

    @Test
    public void testIncorrectResourceLink() {
        Response response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation 1"
                    }
                  ]
                }
                """);
        verify(response, 400, "Incorrect resource link provided conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation 1");
    }

    @Test
    public void testIncorrectResourceLink2() {
        Response response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/%2F"
                    }
                  ]
                }
                """);
        verify(response, 400, "Incorrect resource link provided conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/%2F");
    }

    @Test
    public void testWrongResourceLink() {
        // try to share resource where user is not an owner
        Response response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "conversations/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder/conversation"
                    }
                  ]
                }
                """);
        verify(response, 400, "Resource conversations/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder/conversation does not belong to the user");
    }

    @Test
    public void testShareEmptyResourceList() {
        Response response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": []
                }
                """);
        verify(response, 400, "No resources provided");
    }

    @Test
    public void testAcceptOwnShareRequest() {
        // initialize share request
        Response response = operationRequest("/v1/ops/resource/share/create", """
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

        // accept invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null);
        verify(response, 400, "Resource conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation already belong to you");
    }

    @Test
    public void testCopySharedAccess() {
        // create conversation
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

        // create conversation2
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation2", CONVERSATION_BODY_2);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2\"");

        // copy shared access
        response = operationRequest("/v1/ops/resource/share/copy", """
                {
                  "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                  "destinationUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2"
                }
                """);
        verify(response, 200);

        // verify user2 has access to the conversation2
        response = resourceRequest(HttpMethod.GET, "/folder/conversation2", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_2);

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

        verifyJsonNotExact(response, 200, """
                {
                  "resources" : [ {
                    "name" : "conversation2",
                    "parentPath" : "folder",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
                    "permissions" : [ "READ" ]
                    },
                    {
                    "name" : "conversation",
                    "parentPath" : "folder",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
                    "permissions" : [ "READ" ]
                    }
                  ]
                }
                """);

        // verify user1 has shared_by_me resource
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """);
        verifyJsonNotExact(response, 200, """
                {
                  "resources" : [ {
                    "name" : "conversation2",
                    "parentPath" : "folder",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
                    "permissions" : [ "READ" ]
                    },
                    {
                    "name" : "conversation",
                    "parentPath" : "folder",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
                    "permissions" : [ "READ" ]
                    }
                  ]
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
    public void testCopySharedAccessWithDifferentResourceTypes() {
        // create conversation
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

        // create prompt
        response = send(HttpMethod.PUT, "/v1/prompts/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/prompt", null,  PROMPT_BODY);
        verifyNotExact(response, 200, "\"url\":\"prompts/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/prompt\"");

        // copy shared access
        response = operationRequest("/v1/ops/resource/share/copy", """
                {
                  "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                  "destinationUrl": "prompts/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/prompt"
                }
                """);
        verify(response, 200);

        // verify user2 has access to the conversation2
        response = send(HttpMethod.GET, "/v1/prompts/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/prompt", null, null, "Api-key", "proxyKey2");
        verify(response, 200, PROMPT_BODY);

        // verify user1 has no shared_with_me resources
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION", "PROMPT"],
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
                  "resourceTypes": ["CONVERSATION", "PROMPT"],
                  "with": "me"
                }
                """, "Api-key", "proxyKey2");

        verifyJsonNotExact(response, 200, """
                {
                  "resources" : [ {
                    "name" : "prompt",
                    "parentPath" : "folder",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "prompts/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/prompt",
                    "nodeType" : "ITEM",
                    "resourceType" : "PROMPT",
                    "permissions" : [ "READ" ]
                    },
                    {
                    "name" : "conversation",
                    "parentPath" : "folder",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
                    "permissions" : [ "READ" ]
                    }
                  ]
                }
                """);

        // verify user1 has shared_by_me resource
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION", "PROMPT"],
                  "with": "others"
                }
                """);
        verifyJsonNotExact(response, 200, """
                {
                  "resources" : [ {
                    "name" : "prompt",
                    "parentPath" : "folder",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "prompts/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/prompt",
                    "nodeType" : "ITEM",
                    "resourceType" : "PROMPT",
                    "permissions" : [ "READ" ]
                    },
                    {
                    "name" : "conversation",
                    "parentPath" : "folder",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION",
                    "permissions" : [ "READ" ]
                    }
                  ]
                }
                """);

        // verify user2 has no shared_by_me resources
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION", "PROMPT"],
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
    public void testInvalidSharedAccessCopy() {
        // verify sourUrl must be present
        Response response = operationRequest("/v1/ops/resource/share/copy", """
                {
                  "destinationUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2"
                }
                """);
        verify(response, 400);

        // verify destination url must be present
        response = operationRequest("/v1/ops/resource/share/copy", """
                {
                  "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation"
                }
                """);
        verify(response, 400);

        // verify resources must belong to the user
        response = operationRequest("/v1/ops/resource/share/copy", """
                {
                  "sourceUrl": "conversations/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder/conversation",
                  "destinationUrl": "prompts/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/prompt"
                }
                """);
        verify(response, 400);

        // verify resource should exist
        response = operationRequest("/v1/ops/resource/share/copy", """
                {
                  "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                  "destinationUrl": "prompts/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/prompt"
                }
                """);
        verify(response, 400);
    }

    @Test
    public void testShareFolderWithMetadata() {
        // check no conversations shared with me
        Response response = operationRequest("/v1/ops/resource/share/list", """
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

        // check no conversations shared by me
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources": []
                }
                """);

        // create conversation1
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation1", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation1\"");

        // create conversation2
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation2", CONVERSATION_BODY_2);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation2\"");

        // initialize share request
        response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/"
                    },
                    {
                      "url": "metadata/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder1/"
                    }
                  ]
                }
                """);
        verify(response, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(response.body(), InvitationLink.class);
        assertNotNull(invitationLink);
    }
}

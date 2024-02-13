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
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation%201", "12345");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%201\"");

        // initialize share request
        response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%201"
                    }
                  ]
                }
                """);
        verify(response, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(response.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        // verify invitation details
        response = send(HttpMethod.GET, invitationLink.invitationLink(), null, null);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%201\"");

        // verify user2 do not have access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation%201", null, "Api-key", "proxyKey2");
        verify(response, 403);

        // accept invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2");
        verify(response, 200);

        // verify user2 has access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation%201", null, "Api-key", "proxyKey2");
        verify(response, 200, "12345");

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
                    "name" : "conversation 1",
                    "parentPath" : "folder",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%201",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION"
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
                    "name" : "conversation 1",
                    "parentPath" : "folder",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation%201",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION"
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
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation", "12345");
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
        verify(response, 200, "12345");

        // revoke share access
        response = operationRequest("/v1/ops/resource/share/revoke", """
                {
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation"
                    }
                  ]
                }
                """);
        verify(response, 200);

        // verify user2 do not have access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation", null, "Api-key", "proxyKey2");
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
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation", "12345");
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
        verify(response, 200, "12345");

        // discard share access
        response = operationRequest("/v1/ops/resource/share/discard", """
                {
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation"
                    }
                  ]
                }
                """, "Api-key", "proxyKey2");
        verify(response, 200);

        // verify user2 do not have access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation", null, "Api-key", "proxyKey2");
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
                    "name" : "conversation",
                    "parentPath" : "folder",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                    "nodeType" : "ITEM",
                    "resourceType" : "CONVERSATION"
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
    public void testCleanUpShareAccessWhenOnResourceDeletion() {
        // create conversation
        Response response = resourceRequest(HttpMethod.PUT, "/folder/conversation", "12345");
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
        verify(response, 200, "12345");

        // delete resource
        response = resourceRequest(HttpMethod.DELETE, "/folder/conversation", null);
        verify(response, 200);

        // create resource with same name
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation", "987654");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        // verify user2 has no access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation", null, "Api-key", "proxyKey2");
        verify(response, 403);
    }

    @Test
    public void testResourceDeletionWithoutSharingState() {
        // create conversation
        Response response = resourceRequest(HttpMethod.PUT, "/folder/conversation", "12345");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        // delete resource
        response = resourceRequest(HttpMethod.DELETE, "/folder/conversation", null);
        verify(response, 200);

        // create resource with same name
        response = resourceRequest(HttpMethod.PUT, "/folder/conversation", "987654");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        // download resource
        response = resourceRequest(HttpMethod.GET, "/folder/conversation", null);
        verify(response, 200, "987654");
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
}

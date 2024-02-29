package com.epam.aidial.core;

import com.epam.aidial.core.data.InvitationLink;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class InvitationApiTest extends ResourceBaseTest {

    @Test
    public void testInvitations() {
        Response response = send(HttpMethod.GET, "/v1/invitations", null, null);
        verifyJson(response, 200, """
                {
                  "invitations": []
                }
                """);

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

        String[] elements = invitationLink.invitationLink().split(BlobStorageUtil.PATH_SEPARATOR);
        String invitationId = elements[elements.length - 1];

        // verify invitation can be listed
        response = send(HttpMethod.GET, "/v1/invitations", null, null);
        verifyNotExact(response, 200, "\"id\":\"" + invitationId + "\"");

        // get invitation details by ID
        response = send(HttpMethod.GET, "/v1/invitations/" + invitationId, null, null);
        verifyNotExact(response, 200, "\"id\":\"" + invitationId + "\"");

        // get invitation details by ID
        response = send(HttpMethod.GET, "/v1/invitations/" + invitationId, null, null, "Api-key", "proxyKey2");
        verifyNotExact(response, 200, "\"id\":\"" + invitationId + "\"");

        // delete invitation
        response = send(HttpMethod.DELETE, "/v1/invitations/" + invitationId, null, null, "Api-key", "proxyKey2");
        verify(response, 403, "You are not invitation owner");

        // delete invitation
        response = send(HttpMethod.DELETE, "/v1/invitations/" + invitationId, null, null);
        verify(response, 200);
    }

    @Test
    public void testInvitationsCleanedUpAfterResourceDeletion() {
        // create conversation
        Response response = resourceRequest(HttpMethod.PUT, "/folder/conversation%201", CONVERSATION_BODY_1);
        verify(response, 200);

        response = send(HttpMethod.GET, "/v1/invitations", null, null);
        verifyJson(response, 200, """
                {
                  "invitations": []
                }
                """);

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

        String[] elements = invitationLink.invitationLink().split(BlobStorageUtil.PATH_SEPARATOR);
        String invitationId = elements[elements.length - 1];

        // verify invitation can be listed
        response = send(HttpMethod.GET, "/v1/invitations", null, null);
        verifyNotExact(response, 200, "\"id\":\"" + invitationId + "\"");

        // get invitation details by ID
        response = send(HttpMethod.GET, "/v1/invitations/" + invitationId, null, null);
        verifyNotExact(response, 200, "\"id\":\"" + invitationId + "\"");

        // get invitation details by ID
        response = send(HttpMethod.GET, "/v1/invitations/" + invitationId, null, null, "Api-key", "proxyKey2");
        verifyNotExact(response, 200, "\"id\":\"" + invitationId + "\"");

        // delete resource
        response = resourceRequest(HttpMethod.DELETE, "/folder/conversation%201", null);
        verify(response, 200);

        // verify invitations are empty
        response = send(HttpMethod.GET, "/v1/invitations", null, null);
        verifyJson(response, 200, """
                {
                  "invitations": []
                }
                """);
    }

    @Test
    public void testInvitationNotFound() {
        Response response = send(HttpMethod.GET, "/v1/invitations/asdasd", null, null);
        verify(response, 404);

        response = send(HttpMethod.DELETE, "/v1/invitations/asdasd", null, null);
        verify(response, 404);
    }
}

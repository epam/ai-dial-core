package com.epam.aidial.core;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

class PublicationApiTest extends ResourceBaseTest {

    @Test
    void testPublicationCreation() {
        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", "12345");
        verify(response, 200);

        response = operationRequest("/v1/ops/publications/create", """
                {
                  "url": "publications/%s/",
                  "sourceUrl": "%s/my/folder/",
                  "targetUrl": "public/folder/",
                  "resources": [
                    {
                      "sourceUrl": "conversations/%s/my/folder/conversation",
                      "version": "1"
                    }
                  ]
                }
                """.formatted(bucket, bucket, bucket));

        verifyPretty(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "sourceUrl" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/",
                  "targetUrl" : "public/folder/",
                  "status" : "PENDING",
                  "createdAt" : 0
                }
                """);

        response = operationRequest("/v1/ops/publications/get", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123"
                }
                """);

        verifyPretty(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                  "sourceUrl" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/",
                  "targetUrl" : "public/folder/",
                  "status" : "PENDING",
                  "createdAt" : 0,
                  "resources" : [ {
                    "sourceUrl" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/conversation",
                    "targetUrl" : "conversations/public/folder/conversation.1",
                    "reviewUrl" : "conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation",
                    "version" : "1"
                  } ]
                }
                """);

        response = operationRequest("/v1/ops/publications/list", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/"
                }
                """);

        verifyPretty(response, 200, """
                {
                  "publications" : [ {
                    "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123",
                    "sourceUrl" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my/folder/",
                    "targetUrl" : "public/folder/",
                    "status" : "PENDING",
                    "createdAt" : 0
                  } ]
                }
                """);

        response = send(HttpMethod.GET,  "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verify(response, 200, "12345");

        response = send(HttpMethod.PUT,  "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verify(response, 403);

        response = send(HttpMethod.DELETE,  "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verify(response, 403);
    }

    @Test
    void testPublicationDeletion() {
        Response response = resourceRequest(HttpMethod.PUT, "/my/folder/conversation", "12345");
        verify(response, 200);

        response = operationRequest("/v1/ops/publications/create", """
                {
                  "url": "publications/%s/",
                  "sourceUrl": "%s/my/folder/",
                  "targetUrl": "public/folder/",
                  "resources": [
                    {
                      "sourceUrl": "conversations/%s/my/folder/conversation",
                      "version": "1"
                    }
                  ]
                }
                """.formatted(bucket, bucket, bucket));
        verify(response, 200);

        response = send(HttpMethod.GET,  "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verify(response, 200);

        response = operationRequest("/v1/ops/publications/delete", """
                {
                  "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0123"
                }
                """);
        verify(response, 200);

        response = send(HttpMethod.GET,  "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verify(response, 404);

        response = send(HttpMethod.PUT,  "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verify(response, 403);

        response = send(HttpMethod.DELETE,  "/v1/conversations/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhEstv1KXNzCiw693js8BLmo/conversation");
        verify(response, 403);
    }
}
package com.epam.aidial.core;

import io.vertx.core.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;

@Slf4j
class ResourceApiTest extends ResourceBaseTest {

    @Test
    void testWorkflow() {
        Response response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verify(response, 404, "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation");

        response = metadata("/folder/");
        verify(response, 404, "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/");

        response = metadata("/");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/\"");

        response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        response = metadata("/?recursive=true");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1, "if-none-match", "*");
        verifyNotExact(response, 409, "Resource already exists: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation");

        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verifyJson(response, 200, CONVERSATION_BODY_1);

        response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_2);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder");

        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verifyJson(response, 200, CONVERSATION_BODY_2);

        response = metadata("/folder/");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        response = metadata("/");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/\"");

        response = resourceRequest(HttpMethod.DELETE, "/folder/conversation");
        verify(response, 200, "");

        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verify(response, 404, "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation");

        response = resourceRequest(HttpMethod.DELETE, "/folder/conversation");
        verify(response, 404, "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation");

        response = metadata("/folder/");
        verify(response, 404, "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/");
    }

    @Test
    public void testFileUploadWithInvalidResourcePath() {
        Response response = resourceRequest(HttpMethod.PUT, "/folder/conversation.", CONVERSATION_BODY_1);
        verify(response, 400);

        response = resourceRequest(HttpMethod.PUT, "/folder./conversation", CONVERSATION_BODY_1);
        verify(response, 400);

        response = resourceRequest(HttpMethod.GET, "/folder1/conversation.");
        verify(response, 404);

        response = resourceRequest(HttpMethod.GET, "/folder1./conversation");
        verify(response, 404);
    }

    @Test
    void testMaxKeySize() {
        Response response = resourceRequest(HttpMethod.PUT, "/" + "1".repeat(900), "body");
        verify(response, 400, "Resource path exceeds max allowed size: 900");
    }

    @Test
    void testMaxContentSize() {
        Response response = resourceRequest(HttpMethod.PUT, "/folder/big", "1".repeat(1024 * 1024 + 1));
        verify(response, 413, "Resource size: 1048577 exceeds max limit: 1048576");
    }

    @Test
    void testUnsupportedIfNoneMatchHeader() {
        Response response = resourceRequest(HttpMethod.PUT, "/folder/big", "1", "if-none-match", "unsupported");
        verify(response, 400, "only header if-none-match=* is supported");
    }

    @Test
    void testRandom() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 1000; i++) {
            int type = random.nextInt(0, 3);
            int id = random.nextInt(0, 200);
            int size = random.nextInt(0, 2);
            String body = size == 0 ? CONVERSATION_BODY_1 : CONVERSATION_BODY_2;
            String path = "/folder1/folder2/conversation" + id;
            String notFound = "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST" + path;

            if (type == 0) {
                Response resource = resourceRequest(HttpMethod.PUT, path, body);
                verifyNotExact(resource, 200, path);

                resource = resourceRequest(HttpMethod.GET, path);
                verify(resource, 200, body);
                continue;
            }

            if (type == 1) {
                Response response = resourceRequest(HttpMethod.DELETE, path);
                verify(response, response.ok() ? 200 : 404, response.ok() ? "" : notFound);
                continue;
            }

            if (type == 2) {
                Response response = resourceRequest(HttpMethod.GET, path);
                if (response.status() == 200) {
                    // flip body
                    body = size == 0 ? CONVERSATION_BODY_2 : CONVERSATION_BODY_1;
                    Response resource = resourceRequest(HttpMethod.PUT, path, body);
                    verifyNotExact(resource, 200, path);

                    resource = resourceRequest(HttpMethod.GET, path);
                    verify(resource, 200, body);
                } else {
                    verify(response, 404, notFound);
                }
                continue;
            }

            throw new IllegalStateException("Unreachable code");
        }
    }
}
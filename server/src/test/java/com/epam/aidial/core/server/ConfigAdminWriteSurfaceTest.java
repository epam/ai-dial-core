package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

/**
 * HTTP integration tests for slice 3S.4: admin write paths for {@code files},
 * {@code prompts}, {@code conversations} in {@code public/}. The production code
 * (admin authz preflight on {@code AccessControlBaseController}) ships with 1S.5;
 * this slice fills the integration-test surface that 1S.5 left partial — adds
 * conversations writes, files multipart upload + delete, and prompt delete.
 */
public class ConfigAdminWriteSurfaceTest extends ResourceBaseTest {

    @Test
    void testAdminWritesAndDeletesPublicConversation() {
        verify(send(HttpMethod.PUT, "/v1/conversations/public/admin-conv", null,
                CONVERSATION_BODY_1, "authorization", "admin"), 200);

        verify(send(HttpMethod.GET, "/v1/conversations/public/admin-conv", null, "",
                "authorization", "admin"), 200);

        verify(send(HttpMethod.DELETE, "/v1/conversations/public/admin-conv", null, "",
                "authorization", "admin"), 200);

        verify(send(HttpMethod.GET, "/v1/conversations/public/admin-conv", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    void testAdminDeletesPublicPrompt() {
        verify(send(HttpMethod.PUT, "/v1/prompts/public/admin-prompt-to-delete", null,
                PROMPT_BODY, "authorization", "admin"), 200);

        verify(send(HttpMethod.DELETE, "/v1/prompts/public/admin-prompt-to-delete", null, "",
                "authorization", "admin"), 200);

        verify(send(HttpMethod.GET, "/v1/prompts/public/admin-prompt-to-delete", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    void testAdminUploadsAndDeletesPublicFile() {
        verify(upload(HttpMethod.PUT, "/v1/files/public/admin-shared.txt", null,
                "admin shared content", "authorization", "admin"), 200);

        verify(send(HttpMethod.GET, "/v1/files/public/admin-shared.txt", null, "",
                "authorization", "admin"), 200, "admin shared content");

        verify(send(HttpMethod.DELETE, "/v1/files/public/admin-shared.txt", null, "",
                "authorization", "admin"), 200);

        verify(send(HttpMethod.GET, "/v1/files/public/admin-shared.txt", null, "",
                "authorization", "admin"), 404);
    }
}

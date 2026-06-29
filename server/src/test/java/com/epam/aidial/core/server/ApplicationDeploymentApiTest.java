package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApplicationDeploymentApiTest extends ResourceBaseTest {

    private TestWebServer webServer;

    @BeforeEach
    void initWebServer() {
        webServer = new TestWebServer(17321);
    }

    @AfterEach
    void destroyDeploymentService() {
        try (TestWebServer server = webServer) {
            // closing
        }
    }

    @Test
    void testApplicationCreated() {
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", null, """
                {
                  "display_name": "My App",
                  "display_version": "1.0",
                  "icon_url": "http://application1/icon.svg",
                  "description": "My App Description",
                  "function": {
                    "runtime": "python3.11",
                    "source_folder": "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "mapping" : {
                      "chat_completion" : "/application"
                    },
                    "env": {
                      "VAR": "VAL"
                    }
                  }
                }
                """);
        verify(response, 200);
        id++;
    }

    @Test
    void testApplicationStarted() {
        testApplicationCreated();

        Response response = upload(HttpMethod.PUT, "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/app.py", null, """
                some python code
                """);
        verify(response, 200);

        webServer.map(HttpMethod.POST, "/v1/image/0123", 200, """
                :heartbeat
                
                event: result
                data: {}
                """);
        webServer.map(HttpMethod.POST, "/v1/deployment/0123", 200, """
                event: result
                data: {"url":"http://localhost:17321"}
                """);

        response = send(HttpMethod.POST, "/v1/ops/application/deploy", null, """
                {
                  "url": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app"
                }
                """);
        verifyJsonNotExact(response, 200, """
                {
                  "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                  "display_name" : "My App",
                  "display_version" : "1.0",
                  "icon_url" : "http://application1/icon.svg",
                  "description" : "My App Description",
                  "reference" : "@ignore",
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "responses_defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "max_retry_attempts" : 1,
                  "dependencies" : [ ],
                  "function" : {
                    "id" : "0123",
                    "runtime": "python3.11",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "DEPLOYING",
                    "mapping" : {
                      "chat_completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  },
                  "routes" : { }
                }
                """);

        response = awaitApplicationStatus("/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", "DEPLOYED");
        verifyJsonNotExact(response, 200, """
                {
                  "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                  "endpoint" : "http://localhost:17321/application",
                  "display_name" : "My App",
                  "display_version" : "1.0",
                  "icon_url" : "http://application1/icon.svg",
                  "description" : "My App Description",
                  "reference" : "@ignore",
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "responses_defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "max_retry_attempts" : 1,
                  "dependencies" : [ ],
                  "author" : "EPM-RTC-GPT",
                  "created_at" : "@ignore",
                  "updated_at" : "@ignore",
                  "function" : {
                    "id" : "0123",
                    "runtime": "python3.11",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "DEPLOYED",
                    "mapping" : {
                      "chat_completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  },
                  "routes" : { }
                }
                """);
    }

    @Test
    void testApplicationStopped() {
        testApplicationStarted();

        webServer.map(HttpMethod.DELETE, "/v1/image/0123", 200,
                """
                event: result
                data: {}
                """);
        webServer.map(HttpMethod.DELETE, "/v1/deployment/0123", 200,
                """
                event: result
                data: {"deleted":true}
                """);

        Response response = send(HttpMethod.POST, "/v1/ops/application/undeploy", null, """
                {
                  "url": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app"
                }
                """);
        verifyJsonNotExact(response, 200, """
                {
                  "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                  "display_name" : "My App",
                  "display_version" : "1.0",
                  "icon_url" : "http://application1/icon.svg",
                  "description" : "My App Description",
                  "reference" : "@ignore",
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "responses_defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "max_retry_attempts" : 1,
                  "dependencies" : [ ],
                  "function" : {
                    "id" : "0123",
                    "runtime": "python3.11",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "UNDEPLOYING",
                    "mapping" : {
                      "chat_completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  },
                   "routes" : { }
                }
                """);

        response = awaitApplicationStatus("/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", "UNDEPLOYED");
        verifyJsonNotExact(response, 200, """
                {
                  "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                  "display_name" : "My App",
                  "display_version" : "1.0",
                  "icon_url" : "http://application1/icon.svg",
                  "description" : "My App Description",
                  "reference" : "@ignore",
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "responses_defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "max_retry_attempts" : 1,
                  "dependencies" : [ ],
                  "author" : "EPM-RTC-GPT",
                  "created_at" : "@ignore",
                  "updated_at" : "@ignore",
                  "function" : {
                    "id" : "0123",
                    "runtime": "python3.11",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "UNDEPLOYED",
                    "mapping" : {
                      "chat_completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  },
                  "routes" : { }
                }
                """);
    }

    @Test
    void testApplicationRestarted() {
        testApplicationStarted();

        webServer.map(HttpMethod.DELETE, "/v1/image/0123", 200,
                """
                event: result
                data: {}
                """);
        webServer.map(HttpMethod.DELETE, "/v1/deployment/0123", 200,
                """
                event: result
                data: {"deleted":true}
                """);

        Response response = send(HttpMethod.POST, "/v1/ops/application/redeploy", null, """
                {
                  "url": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app"
                }
                """);
        verifyJsonNotExact(response, 200, """
                {
                  "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                  "display_name" : "My App",
                  "display_version" : "1.0",
                  "icon_url" : "http://application1/icon.svg",
                  "description" : "My App Description",
                  "reference" : "@ignore",
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "responses_defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "max_retry_attempts" : 1,
                  "dependencies" : [ ],
                  "function" : {
                    "id" : "0123",
                    "runtime": "python3.11",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "UNDEPLOYING",
                    "mapping" : {
                      "chat_completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  },
                  "routes" : { }
                }
                """);

        response = awaitApplicationStatus("/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", "DEPLOYED");
        verifyJsonNotExact(response, 200, """
                {
                  "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                  "endpoint" : "http://localhost:17321/application",
                  "display_name" : "My App",
                  "display_version" : "1.0",
                  "icon_url" : "http://application1/icon.svg",
                  "description" : "My App Description",
                  "reference" : "@ignore",
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "responses_defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "max_retry_attempts" : 1,
                  "dependencies" : [ ],
                  "author" : "EPM-RTC-GPT",
                  "created_at" : "@ignore",
                  "updated_at" : "@ignore",
                  "function" : {
                    "id" : "0123",
                    "runtime": "python3.11",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "DEPLOYED",
                    "mapping" : {
                      "chat_completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  },
                  "routes" : { }
                }
                """);
    }

    @Test
    void testApplicationFailed() {
        testApplicationCreated();

        webServer.map(HttpMethod.DELETE, "/v1/image/0123", 200,
                """
                event: result
                data: {}
                """);
        webServer.map(HttpMethod.DELETE, "/v1/deployment/0123", 200,
                """
                event: result
                data: {"deleted":true}
                """);

        Response response = send(HttpMethod.POST, "/v1/ops/application/deploy", null, """
                {
                  "url": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app"
                }
                """);
        verifyJsonNotExact(response, 200, """
                {
                  "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                  "display_name" : "My App",
                  "display_version" : "1.0",
                  "icon_url" : "http://application1/icon.svg",
                  "description" : "My App Description",
                  "reference" : "@ignore",
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "responses_defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "max_retry_attempts" : 1,
                  "dependencies" : [ ],
                  "function" : {
                    "id" : "0123",
                    "runtime": "python3.11",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "DEPLOYING",
                    "mapping" : {
                      "chat_completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  },
                  "routes" : { }
                }
                """);

        response = awaitApplicationStatus("/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", "FAILED");
        verifyJsonNotExact(response, 200, """
                {
                  "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                  "display_name" : "My App",
                  "display_version" : "1.0",
                  "icon_url" : "http://application1/icon.svg",
                  "description" : "My App Description",
                  "reference" : "@ignore",
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "responses_defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "max_retry_attempts" : 1,
                  "dependencies" : [ ],
                  "author" : "EPM-RTC-GPT",
                  "created_at" : "@ignore",
                  "updated_at" : "@ignore",
                  "function" : {
                    "id" : "0123",
                    "runtime": "python3.11",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "FAILED",
                    "error" : "Source folder is empty",
                    "mapping" : {
                      "chat_completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  },
                  "routes" : { }
                }
                """);
    }

    @Test
    void testApplicationDeleted() {
        testApplicationStopped();
        Response response = send(HttpMethod.DELETE, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", null, null);
        verify(response, 200);
    }

    @Test
    void testRecoverApplicationAfterFailedStart() throws Exception {
        testApplicationCreated();

        Response response = send(HttpMethod.POST, "/v1/ops/application/deploy", null, """
                {
                  "url": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app"
                }
                """);
        verifyJsonNotExact(response, 200, """
                {
                  "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                  "display_name" : "My App",
                  "display_version" : "1.0",
                  "icon_url" : "http://application1/icon.svg",
                  "description" : "My App Description",
                  "reference" : "@ignore",
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "responses_defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "max_retry_attempts" : 1,
                  "dependencies" : [ ],
                  "function" : {
                    "id" : "0123",
                    "runtime": "python3.11",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "DEPLOYING",
                    "mapping" : {
                      "chat_completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  },
                   "routes" : { }
                }
                """);

        Thread.sleep(300); // does not cause tests to be fluky

        awaitApplicationStatus("/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", "DEPLOYING");
        webServer.map(HttpMethod.DELETE, "/v1/image/0123", 200,
                """
                event: result
                data: {}
                """);
        webServer.map(HttpMethod.DELETE, "/v1/deployment/0123", 200,
                """
                event: result
                data: {"deleted":false}
                """);
        awaitApplicationStatus("/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", "FAILED");
    }

    @Test
    void testRecoverApplicationAfterFailedStop() throws Exception {
        testApplicationStarted();

        Response response = send(HttpMethod.POST, "/v1/ops/application/undeploy", null, """
                {
                  "url": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app"
                }
                """);
        verifyJsonNotExact(response, 200, """
                {
                  "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                  "display_name" : "My App",
                  "display_version" : "1.0",
                  "icon_url" : "http://application1/icon.svg",
                  "description" : "My App Description",
                  "reference" : "@ignore",
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "responses_defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "max_retry_attempts" : 1,
                  "dependencies" : [ ],
                  "function" : {
                    "id" : "0123",
                    "runtime": "python3.11",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "UNDEPLOYING",
                    "mapping" : {
                      "chat_completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  },
                  "routes" : { }
                }
                """);

        Thread.sleep(300); // does not cause tests to be fluky

        awaitApplicationStatus("/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", "UNDEPLOYING");
        webServer.map(HttpMethod.DELETE, "/v1/image/0123", 200,
                """
                event: result
                data: {}
                """);
        webServer.map(HttpMethod.DELETE, "/v1/deployment/0123", 200,
                """
                event: result
                data: {"deleted":true}
                """);
        awaitApplicationStatus("/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", "UNDEPLOYED");
    }

    @Test
    void testAccessToCopiedSourceFiles() {
        testApplicationStarted();

        Response response = send(HttpMethod.GET, "/v1/metadata/files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/", null, null);
        verify(response, 403);

        response = send(HttpMethod.DELETE, "/v1/files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/", null, null);
        verify(response, 403);

        response = send(HttpMethod.GET, "/v1/metadata/files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/", null, null,
                "authorization", "user");
        verify(response, 403);

        response = send(HttpMethod.DELETE, "/v1/files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/", null, null,
                "authorization", "user");
        verify(response, 403);
    }

    @Test
    void testApiWhenStarted() {
        testApplicationStarted();

        String answer = """
                {
                  "id": "chatcmpl-7VfMTgj3ljKdGKS2BEIwloII3IoO0",
                  "object": "chat.completion",
                  "created": 1687781517,
                  "model": "gpt-35-turbo",
                  "choices": [
                    {
                      "index": 0,
                      "finish_reason": "stop",
                      "message": {
                        "role": "assistant",
                        "content": "As an AI language model, I do not have emotions like humans. However, I am functioning well and ready to assist you. How can I help you today?"
                      }
                    }
                  ],
                  "usage": {
                    "completion_tokens": 33,
                    "prompt_tokens": 19,
                    "total_tokens": 52
                  }
                }
                """;
        webServer.map(HttpMethod.POST, "/application", 200, answer);

        Response response = send(HttpMethod.POST, "/openai/deployments/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/chat/completions", null,
                """
                        {
                          "messages": [
                            {
                              "role": "system",
                              "content": ""
                            },
                            {
                              "role": "user",
                              "content": "How are you?"
                            }
                          ],
                          "max_tokens": 500,
                          "temperature": 1,
                          "stream": true
                        }
                        """, "content-type", "application/json");
        verify(response, 200, answer);
    }

    @Test
    void testApiWhenStopped() {
        testApplicationStopped();
        webServer.map(HttpMethod.POST, "/application", 404, "");

        Response response = send(HttpMethod.POST, "/openai/deployments/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/chat/completions", null,
                """
                        {
                          "messages": [
                            {
                              "role": "system",
                              "content": ""
                            },
                            {
                              "role": "user",
                              "content": "How are you?"
                            }
                          ],
                          "max_tokens": 500,
                          "temperature": 1,
                          "stream": true
                        }
                        """, "content-type", "application/json");
        verify(response, 503);
    }

    @Test
    void testControllerError() {
        testApplicationCreated();

        Response response = upload(HttpMethod.PUT, "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/app.py", null, """
                some python code
                """);
        verify(response, 200);

        webServer.map(HttpMethod.POST, "/v1/image/0123", 200, """
                event: result
                data: {}
                """);
        webServer.map(HttpMethod.POST, "/v1/deployment/0123", 200, """
                event: error
                data: {"message":"failed to deploy"}
                """);

        webServer.map(HttpMethod.DELETE, "/v1/image/0123", 200,
                """
                event: result
                data: {}
                """);
        webServer.map(HttpMethod.DELETE, "/v1/deployment/0123", 200,
                """
                event: result
                data: {"deleted":true}
                """);

        response = send(HttpMethod.POST, "/v1/ops/application/deploy", null, """
                {
                  "url": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app"
                }
                """);
        verify(response, 200);

        response = awaitApplicationStatus("/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", "FAILED");
        verifyJsonNotExact(response, 200, """
                {
                  "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                  "display_name" : "My App",
                  "display_version" : "1.0",
                  "icon_url" : "http://application1/icon.svg",
                  "description" : "My App Description",
                  "reference" : "@ignore",
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "responses_defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "max_retry_attempts" : 1,
                  "dependencies" : [ ],
                  "author" : "EPM-RTC-GPT",
                  "created_at" : "@ignore",
                  "updated_at" : "@ignore",
                  "function" : {
                    "id" : "0123",
                    "runtime": "python3.11",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "FAILED",
                    "error" : "@ignore",
                    "mapping" : {
                      "chat_completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  },
                  "routes" : { }
                }
                """);
    }

    @Test
    void testPublication() {
        testApplicationCreated();
        Response response = upload(HttpMethod.PUT, "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/app.py", null, """
                some python code
                """);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", """
                {
                  "targetFolder": "public/",
                  "resources": [
                    {
                      "action": "ADD",
                      "sourceUrl": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                      "targetUrl": "applications/public/my-app"
                    }
                  ]
                }
                """);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/approve", """
                {
                "url": "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0125"
                }
                """, "authorization", "admin");
        verifyJson(response, 200, """
                {
                  "url" : "publications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/0125",
                  "targetFolder" : "public/",
                  "status" : "APPROVED",
                  "createdAt" : 0,
                  "resources" : [ {
                    "action" : "ADD",
                    "sourceUrl" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                    "targetUrl" : "applications/public/my-app",
                    "reviewUrl" : "applications/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhG9cWs5cubLjt6DVqa4wmnj/my-app",
                    "publishCredentials": false
                  } ],
                  "resourceTypes" : [ "APPLICATION" ],
                  "author" : "EPM-RTC-GPT"
                }
                """);

        response = send(HttpMethod.GET, "/v1/applications/public/my-app",
                null, null, "authorization", "admin");
        verifyJsonNotExact(response, 200, """
                {
                  "name" : "applications/public/my-app",
                  "display_name" : "My App",
                  "display_version" : "1.0",
                  "icon_url" : "http://application1/icon.svg",
                  "description" : "My App Description",
                  "reference" : "@ignore",
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "responses_defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "max_retry_attempts" : 1,
                  "dependencies" : [ ],
                  "author" : "EPM-RTC-GPT",
                  "created_at" : "@ignore",
                  "updated_at" : "@ignore",
                  "function" : {
                    "id" : "0127",
                    "runtime": "python3.11",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/BHSYDZdoJ31Kxh6XahLj91R6sRAnZtraHCQmDeK3uajc/",
                    "target_folder" : "files/BHSYDZdoJ31Kxh6XahLj91R6sRAnZtraHCQmDeK3uajc/",
                    "status" : "UNDEPLOYED",
                    "mapping" : {
                      "chat_completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  },
                  "routes" : { }
                }
                """);

        response = send(HttpMethod.GET, "/v1/applications/public/my-app",
                null, null, "authorization", "user");
        verify(response, 200);

        webServer.map(HttpMethod.DELETE, "/v1/image/0127", 200,
                """
                event: result
                data: {}
                """);
        webServer.map(HttpMethod.DELETE, "/v1/deployment/0127", 200,
                """
                event: result
                data: {"deleted":true}
                """);

        webServer.map(HttpMethod.POST, "/v1/image/0127", 200, """
                :heartbeat
                
                event: result
                data: {}
                """);
        webServer.map(HttpMethod.POST, "/v1/deployment/0127", 200, """
                event: result
                data: {"url":"http://localhost:17321"}
                """);

        response = send(HttpMethod.POST, "/v1/ops/application/deploy", null, """
                {
                  "url": "applications/public/my-app"
                }
                """, "authorization", "admin");
        verify(response, 200);

        response = awaitApplicationStatus("/v1/applications/public/my-app", "DEPLOYED");
        verify(response, 200);

        response = send(HttpMethod.POST, "/v1/ops/application/undeploy", null, """
                {
                  "url": "applications/public/my-app"
                }
                """, "authorization", "admin");
        verify(response, 200);

        response = awaitApplicationStatus("/v1/applications/public/my-app", "UNDEPLOYED");
        verify(response, 200);


        response = operationRequest("/v1/ops/publication/create", """
                {
                  "targetFolder": "public/",
                  "resources": [
                    {
                      "action": "DELETE",
                      "targetUrl": "applications/public/my-app"
                    }
                  ]
                }
                """);
        verify(response, 200);

        response = operationRequest("/v1/ops/publication/create", """
                {
                  "targetFolder": "public/",
                  "resources": [
                    {
                      "action": "DELETE",
                      "targetUrl": "applications/public/my-app"
                    }
                  ]
                }
                """, "authorization", "user");
        verify(response, 400, "Target application has a different author: applications/public/my-app");
    }

    @Test
    void testLogs() {
        testApplicationStarted();
        webServer.map(HttpMethod.GET, "/v1/deployment/0123/logs", 200, """
                {
                  "logs": [
                    {"instance": "instance1", "content": "Log message #1"},
                    {"instance": "instance2", "content": "Log message #2"}
                  ]
                }
                """);

        Response response = send(HttpMethod.POST, "/v1/ops/application/logs", null, """
                {
                  "url": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app"
                }
                """);
        verifyJsonNotExact(response, 200, """
                {
                  "logs" : [ {
                    "instance" : "instance1",
                    "content" : "Log message #1"
                  }, {
                    "instance" : "instance2",
                    "content" : "Log message #2"
                  } ]
                }
                """);
    }

    @Test
    void testOpenAiApi() {
        testApplicationStarted();

        Response response = send(HttpMethod.GET, "/openai/applications/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app");
        verifyJsonNotExact(response, 200, """
                  {
                    "id" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                    "application" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                    "display_name" : "My App",
                    "display_version" : "1.0",
                    "icon_url" : "http://application1/icon.svg",
                    "description" : "My App Description",
                    "reference" : "@ignore",
                    "object" : "application",
                    "status" : "succeeded",
                    "features" : {
                      "rate" : false,
                      "tokenize" : false,
                      "truncate_prompt" : false,
                      "configuration" : false,
                      "system_prompt" : true,
                      "tools" : false,
                      "seed" : false,
                      "url_attachments" : false,
                      "folder_attachments" : false,
                      "allow_resume" : true,
                      "accessible_by_per_request_key" : true,
                      "content_parts": false,
                      "temperature" : true,
                      "cache" : false,
                      "auto_caching" : false,
                      "parallel_tool_calls" : true,
                      "assistant_attachments_in_request": false,
                      "mcp" : false,
                      "chat_completion" : true,
                      "responses_api" : false,
                      "max_tokens_supported": true,
                      "max_completion_tokens_supported": false,
                      "custom_temperature_supported": true,
                      "reasoning_efforts": []
                    },
                    "defaults" : { },
                    "responses_defaults" : { },
                    "description_keywords" : [ ],
                    "max_retry_attempts" : 1,
                    "owner" : "EPM-RTC-GPT",
                    "created_at" : "@ignore",
                    "updated_at" : "@ignore",
                    "function" : {
                      "id" : "0123",
                      "runtime" : "python3.11",
                      "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                      "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                      "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                      "status" : "DEPLOYED",
                      "mapping" : {
                        "chat_completion" : "/application"
                      },
                      "env" : {
                        "VAR" : "VAL"
                      }
                    },
                    "routes" : { }
                }
                """);

        response = send(HttpMethod.GET, "/openai/applications");
        verifyJsonNotExact(response, 200, """
                {
                  "data" : [ {
                    "id" : "app",
                    "application" : "app",
                    "display_name" : "10k",
                    "icon_url" : "http://localhost:7001/logo10k.png",
                    "description" : "Some description of the application for testing",
                    "reference" : "app",
                    "owner" : "organization-owner",
                    "object" : "application",
                    "status" : "succeeded",
                    "created_at" : 1672534800,
                    "updated_at" : 1672534800,
                    "features" : {
                      "rate" : true,
                      "tokenize" : false,
                      "truncate_prompt" : false,
                      "configuration" : true,
                      "system_prompt" : false,
                      "tools" : false,
                      "seed" : false,
                      "url_attachments" : false,
                      "folder_attachments" : false,
                      "allow_resume" : true,
                      "accessible_by_per_request_key" : true,
                      "content_parts" : false,
                      "temperature" : true,
                      "cache" : false,
                      "auto_caching" : false,
                      "parallel_tool_calls" : true,
                      "assistant_attachments_in_request" : false,
                      "mcp" : false,
                      "chat_completion" : true,
                      "responses_api" : false,
                      "max_tokens_supported" : true,
                      "max_completion_tokens_supported" : false,
                      "custom_temperature_supported" : true,
                      "reasoning_efforts" : [ ]
                    },
                    "defaults" : { },
                    "responses_defaults" : { },
                    "description_keywords" : [ ],
                    "max_retry_attempts" : 1,
                    "routes" : { },
                    "viewer_url" : "http://some-host",
                    "editor_url" : "http://some-host"
                  }, {
                    "id" : "app-route",
                    "application" : "app-route",
                    "display_name" : "10k",
                    "icon_url" : "http://localhost:7001/logo10k.png",
                    "description" : "Some description of the application for testing",
                    "reference" : "app-route",
                    "owner" : "organization-owner",
                    "object" : "application",
                    "status" : "succeeded",
                    "created_at" : 1672534800,
                    "updated_at" : 1672534800,
                    "features" : {
                      "rate" : true,
                      "tokenize" : false,
                      "truncate_prompt" : false,
                      "configuration" : true,
                      "system_prompt" : false,
                      "tools" : false,
                      "seed" : false,
                      "url_attachments" : false,
                      "folder_attachments" : false,
                      "allow_resume" : true,
                      "accessible_by_per_request_key" : true,
                      "content_parts" : false,
                      "temperature" : true,
                      "cache" : false,
                      "auto_caching" : false,
                      "parallel_tool_calls" : true,
                      "assistant_attachments_in_request" : false,
                      "mcp" : false,
                      "chat_completion" : true,
                      "responses_api" : false,
                      "max_tokens_supported" : true,
                      "max_completion_tokens_supported" : false,
                      "custom_temperature_supported" : true,
                      "reasoning_efforts" : [ ]
                    },
                    "defaults" : { },
                    "responses_defaults" : { },
                    "description_keywords" : [ ],
                    "max_retry_attempts" : 1,
                    "routes" : {
                      "index-search" : {
                        "rewritePath" : true,
                        "paths" : [ "/v1/index(/[^/]+)*$" ],
                        "methods" : [ "DELETE", "POST", "PUT" ],
                        "maxRetryAttempts" : 1,
                        "order" : 2147483647,
                        "permissions" : [ ],
                        "attachmentPaths" : {
                          "requestBody" : [ "@.attachments[*].url" ],
                          "responseBody" : [ "@.result.attachedFiles" ]
                        }
                      }
                    }
                  }, {
                    "id" : "app-responses",
                    "application" : "app-responses",
                    "display_name" : "App with Responses API",
                    "reference" : "app-responses",
                    "owner" : "organization-owner",
                    "object" : "application",
                    "status" : "succeeded",
                    "created_at" : 1672534800,
                    "updated_at" : 1672534800,
                    "features" : {
                      "rate" : false,
                      "tokenize" : false,
                      "truncate_prompt" : false,
                      "configuration" : false,
                      "system_prompt" : true,
                      "tools" : false,
                      "seed" : false,
                      "url_attachments" : false,
                      "folder_attachments" : false,
                      "allow_resume" : true,
                      "accessible_by_per_request_key" : true,
                      "content_parts" : false,
                      "temperature" : true,
                      "cache" : false,
                      "auto_caching" : false,
                      "parallel_tool_calls" : true,
                      "assistant_attachments_in_request" : false,
                      "mcp" : false,
                      "chat_completion" : true,
                      "responses_api" : true,
                      "max_tokens_supported" : true,
                      "max_completion_tokens_supported" : false,
                      "custom_temperature_supported" : true,
                      "reasoning_efforts" : [ ]
                    },
                    "defaults" : { },
                    "responses_defaults" : { },
                    "description_keywords" : [ ],
                    "max_retry_attempts" : 1,
                    "routes" : { }
                  }, {
                    "id" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                    "application" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                    "display_name" : "My App",
                    "display_version" : "1.0",
                    "icon_url" : "http://application1/icon.svg",
                    "description" : "My App Description",
                    "reference" : "@ignore",
                    "owner" : "EPM-RTC-GPT",
                    "object" : "application",
                    "status" : "succeeded",
                    "created_at" : "@ignore",
                    "updated_at" : "@ignore",
                    "features" : {
                      "rate" : false,
                      "tokenize" : false,
                      "truncate_prompt" : false,
                      "configuration" : false,
                      "system_prompt" : true,
                      "tools" : false,
                      "seed" : false,
                      "url_attachments" : false,
                      "folder_attachments" : false,
                      "allow_resume" : true,
                      "accessible_by_per_request_key" : true,
                      "content_parts" : false,
                      "temperature" : true,
                      "cache" : false,
                      "auto_caching" : false,
                      "parallel_tool_calls" : true,
                      "assistant_attachments_in_request" : false,
                      "mcp" : false,
                      "chat_completion" : true,
                      "responses_api" : false,
                      "max_tokens_supported" : true,
                      "max_completion_tokens_supported" : false,
                      "custom_temperature_supported" : true,
                      "reasoning_efforts" : [ ]
                    },
                    "defaults" : { },
                    "responses_defaults" : { },
                    "description_keywords" : [ ],
                    "max_retry_attempts" : 1,
                    "function" : {
                      "id" : "0123",
                      "runtime" : "python3.11",
                      "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                      "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                      "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                      "status" : "DEPLOYED",
                      "mapping" : {
                        "chat_completion" : "/application"
                      },
                      "env" : {
                        "VAR" : "VAL"
                      }
                    },
                    "routes" : { }
                  } ],
                  "object" : "list"
                }
                """);
    }

    @SneakyThrows
    private Response awaitApplicationStatus(String path, String status) {
        for (long deadline = System.currentTimeMillis() + 10_000; ; ) {
            Response response = send(HttpMethod.GET, path, null, null);
            verify(response, 200);

            if (response.body().contains(status)) {
                return response;
            }

            if (System.currentTimeMillis() >= deadline) {
                Assertions.fail("Application has not reached the status: " + status + ". Body: " + response.body());
            }

            Thread.sleep(32);
        }
    }

    @Test
    void testPerRequestKeyAccessWhenBuildingImage() {
        testApplicationCreated();

        Response response = upload(HttpMethod.PUT, "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/app.py", null, """
                some python code
                """);
        verify(response, 200);

        webServer.map(HttpMethod.POST, "/v1/image/0123", request -> {
            String perRequestKey = request.getHeaders().get("API-KEY");

            Response answer = send(HttpMethod.GET, "/v1/metadata/files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    null, null, "API-KEY", perRequestKey);
            verify(answer, 200);

            answer = send(HttpMethod.GET, "/v1/files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/app.py",
                    null, null, "API-KEY", perRequestKey);
            verify(answer, 200);

            answer = send(HttpMethod.DELETE, "/v1/files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/app.py",
                    null, null, "API-KEY", perRequestKey);
            verify(answer, 403);

            return new MockResponse().setBody("""
                    :heartbeat
                    
                    event: result
                    data: {}
                    """);
        });

        webServer.map(HttpMethod.POST, "/v1/deployment/0123", 200, """
                event: result
                data: {"url":"http://localhost:17321"}
                """);

        response = send(HttpMethod.POST, "/v1/ops/application/deploy", null, """
                {
                  "url": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app"
                }
                """);
        verify(response, 200);

        response = awaitApplicationStatus("/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", "DEPLOYED");
        verify(response, 200);
    }
}

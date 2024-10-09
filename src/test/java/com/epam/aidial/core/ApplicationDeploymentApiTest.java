package com.epam.aidial.core;

import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApplicationDeploymentApiTest extends ResourceBaseTest {

    private TestWebServer webServer;

    @BeforeEach
    void initWebServer() {
        webServer = new TestWebServer(10001);
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
                    "source_folder": "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "mapping" : {
                      "completion" : "/application"
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
                data: {"url":"http://localhost:10001"}
                """);

        response = send(HttpMethod.POST, "/v1/ops/application/start", null, """
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
                  "user_roles" : [ ],
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "function" : {
                    "id" : "0123",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "STARTING",
                    "mapping" : {
                      "completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  }
                }
                """);

        response = awaitApplicationStatus("/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", "STARTED");
        verifyJsonNotExact(response, 200, """
                {
                  "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                  "endpoint" : "http://localhost:10001/application",
                  "display_name" : "My App",
                  "display_version" : "1.0",
                  "icon_url" : "http://application1/icon.svg",
                  "description" : "My App Description",
                  "reference" : "@ignore",
                  "user_roles" : [ ],
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "function" : {
                    "id" : "0123",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "STARTED",
                    "mapping" : {
                      "completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  }
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

        Response response = send(HttpMethod.POST, "/v1/ops/application/stop", null, """
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
                  "user_roles" : [ ],
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "function" : {
                    "id" : "0123",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "STOPPING",
                    "mapping" : {
                      "completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  }
                }
                """);

        response = awaitApplicationStatus("/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", "STOPPED");
        verifyJsonNotExact(response, 200, """
                {
                  "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                  "display_name" : "My App",
                  "display_version" : "1.0",
                  "icon_url" : "http://application1/icon.svg",
                  "description" : "My App Description",
                  "reference" : "@ignore",
                  "user_roles" : [ ],
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "function" : {
                    "id" : "0123",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "STOPPED",
                    "mapping" : {
                      "completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  }
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

        Response response = send(HttpMethod.POST, "/v1/ops/application/start", null, """
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
                  "user_roles" : [ ],
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "function" : {
                    "id" : "0123",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "STARTING",
                    "mapping" : {
                      "completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  }
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
                  "user_roles" : [ ],
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "function" : {
                    "id" : "0123",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "FAILED",
                    "error" : "Source folder is empty",
                    "mapping" : {
                      "completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  }
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

        Response response = send(HttpMethod.POST, "/v1/ops/application/start", null, """
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
                  "user_roles" : [ ],
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "function" : {
                    "id" : "0123",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "STARTING",
                    "mapping" : {
                      "completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  }
                }
                """);

        Thread.sleep(300); // does not cause tests to be fluky

        awaitApplicationStatus("/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", "STARTING");
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

        Response response = send(HttpMethod.POST, "/v1/ops/application/stop", null, """
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
                  "user_roles" : [ ],
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "function" : {
                    "id" : "0123",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "STOPPING",
                    "mapping" : {
                      "completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  }
                }
                """);

        Thread.sleep(300); // does not cause tests to be fluky

        awaitApplicationStatus("/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", "STOPPING");
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
        awaitApplicationStatus("/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", "STOPPED");
    }

    @Test
    void testAccessToCopiedSourceFiles() {
        testApplicationStarted();

        Response response = send(HttpMethod.GET, "/v1/metadata/files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/", null, null);
        verifyJsonNotExact(response, 200, """
                {
                  "name" : null,
                  "parentPath" : null,
                  "bucket" : "2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt",
                  "url" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                  "nodeType" : "FOLDER",
                  "resourceType" : "FILE",
                  "items" : [ {
                    "name" : "app.py",
                    "parentPath" : null,
                    "bucket" : "2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt",
                    "url" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/app.py",
                    "nodeType" : "ITEM",
                    "resourceType" : "FILE",
                    "updatedAt" : "@ignore",
                    "contentLength" : 17,
                    "contentType" : "text/plain"
                  } ]
                }
                """);

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

        response = send(HttpMethod.POST, "/v1/ops/application/start", null, """
                {
                  "url": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app"
                }
                """);
        verify(response, 200);

        response = awaitApplicationStatus("/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app", "FAILED");;
        verifyJsonNotExact(response, 200, """
                {
                  "name" : "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app",
                  "display_name" : "My App",
                  "display_version" : "1.0",
                  "icon_url" : "http://application1/icon.svg",
                  "description" : "My App Description",
                  "reference" : "@ignore",
                  "user_roles" : [ ],
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "function" : {
                    "id" : "0123",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-app/",
                    "target_folder" : "files/2CZ9i2bcBACFts8JbBu3MdcF8sdwTbELGXeFRV6CVDwnPEU8vWC1y8PpXyRChHQvzt/",
                    "status" : "FAILED",
                    "error" : "failed to deploy",
                    "mapping" : {
                      "completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  }
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
                    "reviewUrl" : "applications/2CZ9i2bcBACFts8JbBu3MdTHfU5imDZBmDVomBuDCkbhG9cWs5cubLjt6DVqa4wmnj/my-app"
                  } ],
                  "resourceTypes" : [ "APPLICATION" ]
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
                  "user_roles" : [ ],
                  "forward_auth_token" : false,
                  "features" : { },
                  "defaults" : { },
                  "interceptors" : [ ],
                  "description_keywords" : [ ],
                  "function" : {
                    "id" : "0127",
                    "author_bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "source_folder" : "files/BHSYDZdoJ31Kxh6XahLj91R6sRAnZtraHCQmDeK3uajc/",
                    "target_folder" : "files/BHSYDZdoJ31Kxh6XahLj91R6sRAnZtraHCQmDeK3uajc/",
                    "status" : "CREATED",
                    "mapping" : {
                      "completion" : "/application"
                    },
                    "env" : {
                      "VAR" : "VAL"
                    }
                  }
                }
                """);

        response = send(HttpMethod.GET, "/v1/applications/public/my-app",
                null, null, "authorization", "user");
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
}

package com.epam.aidial.core;

import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.data.Bucket;
import com.epam.aidial.core.data.FileMetadata;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.ResourceAccessType;
import com.epam.aidial.core.data.ResourceFolderMetadata;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.multipart.MultipartForm;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(VertxExtension.class)
@Slf4j
public class FileApiTest extends ResourceBaseTest {

    private static final String TEST_FILE_CONTENT = "Test file content";

    @Test
    public void testBucket(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        client.get(serverPort, "localhost", "/v1/bucket")
                .putHeader("Api-key", "proxyKey2")
                .as(BodyCodec.json(Bucket.class))
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        assertEquals(200, response.statusCode());
                        assertEquals(new Bucket("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt", null), response.body());
                        context.completeNow();
                    });
                }));
    }

    @Test
    public void testPerRequestBucket(Vertx vertx, VertxTestContext context) {
        // creating per-request API key with proxyKey1 as originator
        // and proxyKey2 caller
        ApiKeyData projectApiKeyData = apiKeyStore.getApiKeyData("proxyKey1").result();
        ApiKeyData apiKeyData2 = new ApiKeyData();
        apiKeyData2.setOriginalKey(projectApiKeyData.getOriginalKey());

        // set deployment ID for proxyKey2
        apiKeyData2.setSourceDeployment("EPM-RTC-RAIL");
        apiKeyStore.assignPerRequestApiKey(apiKeyData2);

        String apiKey2 = apiKeyData2.getPerRequestKey();

        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify proxyKey1 bucket
            client.get(serverPort, "localhost", "/v1/bucket")
                    .putHeader("Api-key", "proxyKey1")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals("{\"bucket\":\"3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST\"}", response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));
            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify proxyKey2 bucket
            client.get(serverPort, "localhost", "/v1/bucket")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals("{\"bucket\":\"7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt\"}", response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));
            return promise.future();
        }).andThen((result) -> {
            // verify per-request key bucket and app-data
            client.get(serverPort, "localhost", "/v1/bucket")
                    .putHeader("Api-key", apiKey2)
                    .as(BodyCodec.json(Bucket.class))
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(new Bucket("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                                    "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/EPM-RTC-RAIL"), response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testEmptyFilesList(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);

        ResourceFolderMetadata emptyBucketResponse = setPermissions(
                new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        null, null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/", List.of()),
                ResourceAccessType.ALL);
        client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/?permissions=true")
                .putHeader("Api-key", "proxyKey2")
                .as(BodyCodec.json(ResourceFolderMetadata.class))
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        assertEquals(200, response.statusCode());
                        assertEquals("application/json", response.getHeader(HttpHeaders.CONTENT_TYPE));
                        assertEquals(emptyBucketResponse, response.body());
                        context.completeNow();
                    });
                }));
    }

    @Test
    public void testMetadataContentType(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);

        ResourceFolderMetadata emptyBucketResponse = setPermissions(
                new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        null, null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/", List.of()),
                ResourceAccessType.ALL);

        client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/?permissions=true")
                .putHeader("Api-key", "proxyKey2")
                .putHeader(HttpHeaders.ACCEPT, MetadataBase.MIME_TYPE)
                .as(BodyCodec.json(ResourceFolderMetadata.class))
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        assertEquals(200, response.statusCode());
                        assertEquals(MetadataBase.MIME_TYPE, response.getHeader(HttpHeaders.CONTENT_TYPE));
                        assertEquals(emptyBucketResponse, response.body());
                        context.completeNow();
                    });
                }));
    }

    @Test
    public void testFileNotFound(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.txt")
                .putHeader("Api-key", "proxyKey2")
                .as(BodyCodec.buffer())
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        assertEquals(404, response.statusCode());
                        assertNull(response.body());
                        context.completeNow();
                    });
                }));
    }

    @Test
    public void testInvalidFileUploadUrl(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/")
                .putHeader("Api-key", "proxyKey2")
                .as(BodyCodec.string())
                .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                        context.succeeding(response -> {
                            context.verify(() -> {
                                assertEquals(400, response.statusCode());
                                assertEquals("File name is missing", response.body());
                                context.completeNow();
                            });
                        })
                );
    }

    @Test
    public void testInvalidFileUploadUrl2(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        client.put(serverPort, "localhost", "/v1/files/testbucket/")
                .putHeader("Api-key", "proxyKey2")
                .as(BodyCodec.string())
                .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                        context.succeeding(response -> {
                            context.verify(() -> {
                                assertEquals(400, response.statusCode());
                                assertEquals("Url has invalid bucket: files/testbucket/", response.body());
                                context.completeNow();
                            });
                        })
                );
    }

    @Test
    public void testDownloadFromAnotherBucket(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(2);
        WebClient client = WebClient.create(vertx);

        FileMetadata expectedFileMetadata = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "file.txt", null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.txt", 17, "text/custom");

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );
            return promise.future();
        }).andThen((mapper) -> {
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.txt")
                    .putHeader("Api-key", "proxyKey1")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(403, response.statusCode());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testFileUploadIntoAnotherBucket(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.txt")
                .putHeader("Api-key", "proxyKey1")
                .as(BodyCodec.string())
                .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                        context.succeeding(response -> {
                            context.verify(() -> {
                                assertEquals(403, response.statusCode());
                                context.completeNow();
                            });
                        })
                );
    }

    @Test
    public void testFileUploadWithInvalidPath(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        Future.succeededFuture().compose(mapper -> {
            Promise<Void> promise = Promise.promise();
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(400, response.statusCode());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );
            return promise.future();
        }).compose(mapper -> {
            Promise<Void> promise = Promise.promise();
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1./file")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(400, response.statusCode());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );
            return promise.future();
        }).andThen(ignore -> {
            // verify file with invalid path can be downloaded
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(404, response.statusCode());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testFileUpload(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        Set<ResourceAccessType> permissions = ResourceAccessType.ALL;
        ResourceFolderMetadata emptyFolderResponse = setPermissions(
                new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        null, null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/", List.of()),
                permissions);
        FileMetadata expectedFileMetadata = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "файл.txt", null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/%D1%84%D0%B0%D0%B9%D0%BB.txt", 17, "text/custom");
        ResourceFolderMetadata expectedFolderMetadata = setPermissions(
                new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        null, null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/",
                        List.of(setPermissions(cloneFileMetadata(expectedFileMetadata), permissions))),
                permissions);

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify no files
            client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/?permissions=true")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(ResourceFolderMetadata.class))
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(emptyFolderResponse, response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/%D1%84%D0%B0%D0%B9%D0%BB.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("файл.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).andThen((result) -> {
            // verify uploaded file can be listed
            client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/?permissions=true")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(ProxyUtil.MAPPER.writeValueAsString(expectedFolderMetadata), response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testFileUploadToAppdata(Vertx vertx, VertxTestContext context) {
        // creating per-request API key with proxyKey1 as originator
        // and proxyKey2 caller
        ApiKeyData projectApiKeyData = apiKeyStore.getApiKeyData("proxyKey1").result();
        ApiKeyData apiKeyData2 = new ApiKeyData();
        apiKeyData2.setOriginalKey(projectApiKeyData.getOriginalKey());
        // set deployment ID for proxyKey2
        apiKeyData2.setSourceDeployment("EPM-RTC-RAIL");
        apiKeyStore.assignPerRequestApiKey(apiKeyData2);

        String apiKey2 = apiKeyData2.getPerRequestKey();

        Checkpoint checkpoint = context.checkpoint(4);
        WebClient client = WebClient.create(vertx);

        Set<ResourceAccessType> permissions = EnumSet.allOf(ResourceAccessType.class);
        FileMetadata expectedFileMetadata = new FileMetadata("3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                "file.txt", "appdata/EPM-RTC-RAIL", "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/EPM-RTC-RAIL/file.txt", 17, "text/custom");
        ResourceFolderMetadata expectedFolderMetadata = setPermissions(
                new ResourceFolderMetadata(ResourceType.FILE, "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                        "EPM-RTC-RAIL", "appdata", "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/EPM-RTC-RAIL/",
                        List.of(setPermissions(cloneFileMetadata(expectedFileMetadata), permissions))),
                permissions);

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify per-request key bucket and app-data
            client.get(serverPort, "localhost", "/v1/bucket")
                    .putHeader("Api-key", apiKey2)
                    .as(BodyCodec.json(Bucket.class))
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(new Bucket("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                                    "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/EPM-RTC-RAIL"), response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));
            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload file to the app data folder with pre-request api key
            client.put(serverPort, "localhost", "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/EPM-RTC-RAIL/file.txt")
                    .putHeader("Api-key", apiKey2)
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify that proxyKey2 can't upload files to the app data with own api-key
            client.put(serverPort, "localhost", "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/EPM-RTC-RAIL/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(403, response.statusCode());
                                    assertEquals("You don't have an access to: files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/EPM-RTC-RAIL/file.txt",
                                            response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).andThen((result) -> {
            // verify uploaded file can be listed by bucket owner
            client.get(serverPort, "localhost", "/v1/metadata/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/EPM-RTC-RAIL/?permissions=true")
                    .putHeader("Api-key", "proxyKey1")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(ProxyUtil.MAPPER.writeValueAsString(expectedFolderMetadata), response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testDownloadSharedFile(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        // creating per-request API key with proxyKey2 as originator
        // and proxyKey1 caller
        ApiKeyData projectApiKeyData = apiKeyStore.getApiKeyData("proxyKey2").result();
        ApiKeyData apiKeyData1 = new ApiKeyData();
        apiKeyData1.setOriginalKey(projectApiKeyData.getOriginalKey());
        // set deployment ID for proxyKey1
        apiKeyData1.setSourceDeployment("EPM-RTC-GPT");
        apiKeyData1.setAttachedFiles(Set.of("files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt"));
        apiKeyStore.assignPerRequestApiKey(apiKeyData1);

        String apiKey1 = apiKeyData1.getPerRequestKey();

        FileMetadata expectedFileMetadata = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "file.txt", "folder1", "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt", 17, "text/plain");

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // proxyKey2 uploads file
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify caller can't download shared file with own api-key
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", "proxyKey1")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(403, response.statusCode());
                            assertEquals("You don't have an access to: files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt", response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).andThen((result) -> {
            // verify pre-request api key can download shared file
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", apiKey1)
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(TEST_FILE_CONTENT, response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testDownloadFileWithinSharedFolder(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        // creating per-request API key with proxyKey2 as originator
        // and proxyKey1 caller
        ApiKeyData projectApiKeyData = apiKeyStore.getApiKeyData("proxyKey2").result();
        ApiKeyData apiKeyData1 = new ApiKeyData();
        apiKeyData1.setOriginalKey(projectApiKeyData.getOriginalKey());
        // set deployment ID for proxyKey1
        apiKeyData1.setSourceDeployment("EPM-RTC-GPT");
        apiKeyData1.setAttachedFolders(List.of("files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/"));
        apiKeyStore.assignPerRequestApiKey(apiKeyData1);

        String apiKey1 = apiKeyData1.getPerRequestKey();

        FileMetadata expectedFileMetadata = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "file.txt", "folder1", "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt", 17, "text/plain");

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // proxyKey2 uploads file
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify caller can't download shared file with own api-key
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", "proxyKey1")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(403, response.statusCode());
                            assertEquals("You don't have an access to: files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt", response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).andThen((result) -> {
            // verify pre-request api key can download shared file
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", apiKey1)
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(TEST_FILE_CONTENT, response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testFileDownload(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(2);
        WebClient client = WebClient.create(vertx);

        FileMetadata expectedFileMetadata = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "file.txt", "folder1", "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt", 17, "text/plain");

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).andThen((result) -> {
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(TEST_FILE_CONTENT, response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testListFileWithFolder(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(4);
        WebClient client = WebClient.create(vertx);

        Set<ResourceAccessType> permissions = ResourceAccessType.ALL;
        ResourceFolderMetadata emptyFolderResponse = setPermissions(
                new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        null, null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/", List.of()),
                permissions);

        FileMetadata expectedFileMetadata1 = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "file.txt", null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.txt", 17, "text/custom");
        FileMetadata expectedFileMetadata2 = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "file.txt", "folder1", "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt", 17, "text/custom");
        ResourceFolderMetadata expectedFolder1Metadata = setPermissions(
                new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        "folder1", null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/"),
                permissions);
        ResourceFolderMetadata expectedRootFolderMetadata = setPermissions(
                new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        null, null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/",
                        List.of(setPermissions(cloneFileMetadata(expectedFileMetadata1), permissions), expectedFolder1Metadata)),
                permissions);

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify no files
            client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/?permissions=true")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(ResourceFolderMetadata.class))
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(emptyFolderResponse, response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file1
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata1, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file2
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata2, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).andThen((result) -> {
            // verify uploaded files can be listed
            client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/?permissions=true")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(ProxyUtil.MAPPER.writeValueAsString(expectedRootFolderMetadata), response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testListFileWithDefaultContentType(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        Set<ResourceAccessType> permissions = EnumSet.allOf(ResourceAccessType.class);
        ResourceFolderMetadata emptyFolderResponse = setPermissions(
                new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        null, null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/", List.of()),
                permissions);

        FileMetadata expectedFileMetadata1 = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "image.png", null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/image.png", 17, "binary/octet-stream");

        FileMetadata expectedImageMetadata = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "image.png", null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/image.png", 17, "image/png");
        ResourceFolderMetadata expectedRootFolderMetadata = setPermissions(
                new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        null, null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/",
                        List.of(setPermissions(cloneFileMetadata(expectedImageMetadata), permissions))),
                permissions);

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify no files
            client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/?permissions=true")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(ResourceFolderMetadata.class))
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(emptyFolderResponse, response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file1
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/image.png")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("filename", TEST_FILE_CONTENT, "binary/octet-stream"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata1, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).andThen((result) -> {
            // verify uploaded files can be listed
            client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/?permissions=true")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(ProxyUtil.MAPPER.writeValueAsString(expectedRootFolderMetadata), response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testFileDelete(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        FileMetadata expectedFileMetadata = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "test_file.txt", null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/test_file.txt", 17, "text/plain");

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/test_file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("test_file.txt", TEST_FILE_CONTENT),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // delete file
            client.delete(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/test_file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).andThen((mapper) -> {
            // try to download deleted file
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/test_file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(404, response.statusCode());
                            checkpoint.flag();
                        });
                    }));
        });
    }


    private static MultipartForm generateMultipartForm(String fileName, String content) {
        return generateMultipartForm(fileName, content, "text/plan");
    }

    private static MultipartForm generateMultipartForm(String fileName, String content, String contentType) {
        return MultipartForm.create().textFileUpload("attachment", fileName, Buffer.buffer(content), contentType);
    }

    private static FileMetadata cloneFileMetadata(FileMetadata expectedFileMetadata) {
        return new FileMetadata(
                expectedFileMetadata.getBucket(),
                expectedFileMetadata.getName(),
                expectedFileMetadata.getParentPath(),
                expectedFileMetadata.getUrl(),
                expectedFileMetadata.getContentLength(),
                expectedFileMetadata.getContentType());
    }

    private static <T extends MetadataBase> T setPermissions(T metadata, Set<ResourceAccessType> permissions) {
        metadata.setPermissions(permissions);
        return metadata;
    }
}

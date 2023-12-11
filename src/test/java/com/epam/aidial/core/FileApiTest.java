package com.epam.aidial.core;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.epam.aidial.core.data.FileMetadata;
import com.epam.aidial.core.data.FolderMetadata;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.multipart.MultipartForm;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(VertxExtension.class)
@Slf4j
public class FileApiTest {

    private static final String TEST_FILE_CONTENT = "Test file content";

    private static AiDial dial;
    private static int serverPort;
    private static Path testDir;

    @BeforeAll
    public static void init() throws Exception {
        // initialize server
        dial = new AiDial();
        testDir = FileUtil.baseTestPath(FileApiTest.class);
        dial.setStorage(FileUtil.buildFsBlobStorage(testDir));
        dial.start();
        serverPort = dial.getServer().actualPort();
    }

    @BeforeEach
    public void setUp() {
        // prepare test directory
        FileUtil.createDir(testDir.resolve("test"));
    }

    @AfterEach
    public void clean() {
        // clean test directory
        FileUtil.deleteDir(testDir);
    }

    @AfterAll
    public static void destroy() {
        // stop server
        dial.stop();
    }

    @Test
    public void testEmptyFilesList(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        client.get(serverPort, "localhost", "/v1/files")
                .putHeader("Api-key", "proxyKey2")
                .bearerTokenAuthentication(generateJwtToken("User1"))
                .addQueryParam("purpose", "metadata")
                .as(BodyCodec.jsonArray())
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        assertEquals(200, response.statusCode());
                        assertEquals(JsonArray.of(), response.body());
                        context.completeNow();
                    });
                }));
    }

    @Test
    public void testFileNotFound(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        client.get(serverPort, "localhost", "/v1/files/test_file.txt")
                .putHeader("Api-key", "proxyKey2")
                .bearerTokenAuthentication(generateJwtToken("User1"))
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
    public void testFileUpload(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        FileMetadata expectedFileMetadata = new FileMetadata("file.txt", "/Users/User1/files", 17, "text/custom");

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify no files
            client.get(serverPort, "localhost", "/v1/files")
                    .putHeader("Api-key", "proxyKey2")
                    .bearerTokenAuthentication(generateJwtToken("User1"))
                    .addQueryParam("purpose", "metadata")
                    .as(BodyCodec.jsonArray())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(JsonArray.of(), response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file
            client.post(serverPort, "localhost", "/v1/files")
                    .putHeader("Api-key", "proxyKey2")
                    .bearerTokenAuthentication(generateJwtToken("User1"))
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
        }).andThen((result) -> {
            // verify uploaded file can be listed
            client.get(serverPort, "localhost", "/v1/files")
                    .putHeader("Api-key", "proxyKey2")
                    .bearerTokenAuthentication(generateJwtToken("User1"))
                    .addQueryParam("purpose", "metadata")
                    .as(BodyCodec.jsonArray())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertIterableEquals(JsonArray.of(JsonObject.mapFrom(expectedFileMetadata)), response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testFileDownload(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        FileMetadata expectedFileMetadata = new FileMetadata("file.txt", "/Users/User1/files/folder1", 17, "text/plain");

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file
            client.post(serverPort, "localhost", "/v1/files/folder1")
                    .putHeader("Api-key", "proxyKey2")
                    .bearerTokenAuthentication(generateJwtToken("User1"))
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
            // download by relative path
            client.get(serverPort, "localhost", "/v1/files/folder1/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .addQueryParam("path", "relative")
                    .bearerTokenAuthentication(generateJwtToken("User1"))
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(TEST_FILE_CONTENT, response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).andThen((result) -> {
            // download by absolute path
            client.get(serverPort, "localhost", "/v1/files/Users/User1/files/folder1/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .bearerTokenAuthentication(generateJwtToken("User2"))
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

        FileMetadata expectedFileMetadata1 = new FileMetadata("file.txt", "/Users/User1/files", 17, "text/custom");
        FileMetadata expectedFileMetadata2 = new FileMetadata("file.txt", "/Users/User1/files/folder1", 17, "text/custom");
        FolderMetadata expectedFolderMetadata = new FolderMetadata("folder1", "/Users/User1/files");

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify no files
            client.get(serverPort, "localhost", "/v1/files")
                    .putHeader("Api-key", "proxyKey2")
                    .bearerTokenAuthentication(generateJwtToken("User1"))
                    .addQueryParam("purpose", "metadata")
                    .as(BodyCodec.jsonArray())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(JsonArray.of(), response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file1
            client.post(serverPort, "localhost", "/v1/files")
                    .putHeader("Api-key", "proxyKey2")
                    .bearerTokenAuthentication(generateJwtToken("User1"))
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
            client.post(serverPort, "localhost", "/v1/files/folder1")
                    .putHeader("Api-key", "proxyKey2")
                    .bearerTokenAuthentication(generateJwtToken("User1"))
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
            client.get(serverPort, "localhost", "/v1/files")
                    .putHeader("Api-key", "proxyKey2")
                    .bearerTokenAuthentication(generateJwtToken("User1"))
                    .addQueryParam("purpose", "metadata")
                    .as(BodyCodec.jsonArray())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertIterableEquals(
                                    JsonArray.of(JsonObject.mapFrom(expectedFileMetadata1),
                                            JsonObject.mapFrom(expectedFolderMetadata)),
                                    response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testFileDelete(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        FileMetadata expectedFileMetadata = new FileMetadata("test_file.txt", "/Users/User1/files", 17, "text/plain");

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file
            client.post(serverPort, "localhost", "/v1/files")
                    .putHeader("Api-key", "proxyKey2")
                    .bearerTokenAuthentication(generateJwtToken("User1"))
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
            client.delete(serverPort, "localhost", "/v1/files/test_file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .bearerTokenAuthentication(generateJwtToken("User1"))
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
            client.get(serverPort, "localhost", "/v1/files/test_file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .bearerTokenAuthentication(generateJwtToken("User1"))
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

    private static String generateJwtToken(String user) {
        Algorithm algorithm = Algorithm.HMAC256("secret_key");
        return JWT.create().withClaim("iss", "issuer").withClaim("sub", user).sign(algorithm);
    }
}

package com.epam.aidial.core;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.epam.aidial.core.config.Storage;
import com.epam.aidial.core.data.FileMetadata;
import com.epam.aidial.core.storage.BlobStorage;
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
import org.jclouds.filesystem.reference.FilesystemConstants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.util.Properties;

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
        testDir = TestFiles.baseTestPath(FileApiTest.class);
        BlobStorage storage = buildFsBlobStorage(testDir);
        dial.start(storage);
        serverPort = dial.getServer().actualPort();
    }

    @BeforeEach
    public void setUp() {
        // prepare test directory
        TestFiles.createDir(testDir.resolve("test"));
    }

    @AfterEach
    public void clean() {
        // clean test directory
        TestFiles.deleteDir(testDir);
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
                        Assertions.assertEquals(200, response.statusCode());
                        Assertions.assertEquals(JsonArray.of(), response.body());
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
                        Assertions.assertEquals(404, response.statusCode());
                        Assertions.assertNull(response.body());
                        context.completeNow();
                    });
                }));
    }

    @Test
    public void testFileUpload(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        // verify no files
        client.get(serverPort, "localhost", "/v1/files")
                .putHeader("Api-key", "proxyKey2")
                .bearerTokenAuthentication(generateJwtToken("User1"))
                .addQueryParam("purpose", "metadata")
                .as(BodyCodec.jsonArray())
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        Assertions.assertEquals(200, response.statusCode());
                        Assertions.assertEquals(JsonArray.of(), response.body());
                        checkpoint.flag();
                    });
                }));

        FileMetadata expectedFileMetadata = new FileMetadata("file.txt", "Users/User1/files", 17, "text/plain");

        // upload test file
        client.post(serverPort, "localhost", "/v1/files")
                .putHeader("Api-key", "proxyKey2")
                .bearerTokenAuthentication(generateJwtToken("User1"))
                .as(BodyCodec.json(FileMetadata.class))
                .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT),
                        context.succeeding(response -> {
                            context.verify(() -> {
                                Assertions.assertEquals(200, response.statusCode());
                                Assertions.assertEquals(expectedFileMetadata, response.body());
                                checkpoint.flag();
                            });
                        })
                );

        // verify uploaded file can be listed
        client.get(serverPort, "localhost", "/v1/files")
                .putHeader("Api-key", "proxyKey2")
                .bearerTokenAuthentication(generateJwtToken("User1"))
                .addQueryParam("purpose", "metadata")
                .as(BodyCodec.jsonArray())
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        Assertions.assertEquals(200, response.statusCode());
                        Assertions.assertIterableEquals(JsonArray.of(JsonObject.mapFrom(expectedFileMetadata)), response.body());
                        checkpoint.flag();
                    });
                }));
    }

    @Test
    public void testFileDownload(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        FileMetadata expectedFileMetadata = new FileMetadata("file.txt", "Users/User1/files/folder1", 17, "text/plain");

        // upload test file
        client.post(serverPort, "localhost", "/v1/files/folder1")
                .putHeader("Api-key", "proxyKey2")
                .bearerTokenAuthentication(generateJwtToken("User1"))
                .as(BodyCodec.json(FileMetadata.class))
                .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT),
                        context.succeeding(response -> {
                            context.verify(() -> {
                                Assertions.assertEquals(200, response.statusCode());
                                Assertions.assertEquals(expectedFileMetadata, response.body());
                                checkpoint.flag();
                            });
                        })
                );

        // download by relative path
        client.get(serverPort, "localhost", "/v1/files/folder1/file.txt")
                .putHeader("Api-key", "proxyKey2")
                .bearerTokenAuthentication(generateJwtToken("User1"))
                .as(BodyCodec.string())
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        Assertions.assertEquals(200, response.statusCode());
                        Assertions.assertEquals(TEST_FILE_CONTENT, response.body());
                        checkpoint.flag();
                    });
                }));

        // download by absolute path
        client.get(serverPort, "localhost", "/v1/files/Users/User1/files/folder1/file.txt")
                .addQueryParam("path", "absolute")
                .putHeader("Api-key", "proxyKey2")
                .bearerTokenAuthentication(generateJwtToken("User2"))
                .as(BodyCodec.string())
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        Assertions.assertEquals(200, response.statusCode());
                        Assertions.assertEquals(TEST_FILE_CONTENT, response.body());
                        checkpoint.flag();
                    });
                }));
    }

    @Test
    public void testFileDelete(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        FileMetadata expectedFileMetadata = new FileMetadata("test_file.txt", "Users/User1/files", 17, "text/plain");

        // upload test file
        client.post(serverPort, "localhost", "/v1/files")
                .putHeader("Api-key", "proxyKey2")
                .bearerTokenAuthentication(generateJwtToken("User1"))
                .as(BodyCodec.json(FileMetadata.class))
                .sendMultipartForm(generateMultipartForm("test_file.txt", TEST_FILE_CONTENT),
                        context.succeeding(response -> {
                            context.verify(() -> {
                                Assertions.assertEquals(200, response.statusCode());
                                Assertions.assertEquals(expectedFileMetadata, response.body());
                                checkpoint.flag();
                            });
                        })
                );

        // delete file
        client.delete(serverPort, "localhost", "/v1/files/test_file.txt")
                .putHeader("Api-key", "proxyKey2")
                .bearerTokenAuthentication(generateJwtToken("User1"))
                .as(BodyCodec.string())
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        Assertions.assertEquals(200, response.statusCode());
                        checkpoint.flag();
                    });
                }));

        // try to download deleted file
        client.get(serverPort, "localhost", "/v1/files/test_file.txt")
                .putHeader("Api-key", "proxyKey2")
                .bearerTokenAuthentication(generateJwtToken("User1"))
                .as(BodyCodec.string())
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        Assertions.assertEquals(404, response.statusCode());
                        checkpoint.flag();
                    });
                }));
    }

    private static BlobStorage buildFsBlobStorage(Path baseDir) {
        Properties properties = new Properties();
        properties.setProperty(FilesystemConstants.PROPERTY_BASEDIR, baseDir.toAbsolutePath().toString());
        Storage storageConfig = new Storage();
        storageConfig.setBucket("test");
        storageConfig.setProvider("filesystem");
        storageConfig.setIdentity("access-key");
        storageConfig.setCredential("secret-key");
        return new BlobStorage(storageConfig, properties);
    }

    private static MultipartForm generateMultipartForm(String fileName, String content) {
        return MultipartForm.create().textFileUpload("attachment", fileName, Buffer.buffer(content), "text/plain");
    }

    private static String generateJwtToken(String user) {
        Algorithm algorithm = Algorithm.HMAC256("secret_key");
        return JWT.create().withClaim("sub", user).sign(algorithm);
    }
}

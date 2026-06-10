package com.epam.aidial.cli.integration;

import com.epam.aidial.cli.config.DialCliFactory;
import com.epam.aidial.cli.service.EnvResolver;
import com.epam.aidial.cli.service.auth.ApiKeyResolver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteCommandsTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        EnvResolver.apiKeyResolver = new ApiKeyResolver(
                Map.of("DIAL_TEST_KEY", "test-key")::get, msg -> null);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        EnvResolver.apiKeyResolver = new ApiKeyResolver();
    }

    private Path writeProfile(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("key.txt"), "test-key");
        Path config = tmp.resolve("config.yaml");
        Files.writeString(config, """
                defaults: { env: dev }
                environments:
                  dev:
                    api_url: "%s"
                    auth: { type: api_key, key_env_var: DIAL_TEST_KEY }
                """.formatted(baseUrl));
        return config;
    }

    private Path writeTwoEnvProfile(Path tmp, String sourceUrl, String targetUrl) throws Exception {
        Path config = tmp.resolve("config.yaml");
        Files.writeString(config, """
                defaults: { env: dev }
                environments:
                  dev:
                    api_url: "%s"
                    auth: { type: api_key, key_env_var: DIAL_TEST_KEY }
                  uat:
                    api_url: "%s"
                    auth: { type: api_key, key_env_var: DIAL_TEST_KEY }
                """.formatted(sourceUrl, targetUrl));
        return config;
    }

    private Path apiKeyFile(Path tmp) throws Exception {
        Path key = tmp.resolve("key.txt");
        if (!Files.exists(key)) {
            Files.writeString(key, "test-key");
        }
        return key;
    }

    private Path writeFile(Path tmp, String name, String body) throws IOException {
        Path file = tmp.resolve(name);
        Files.writeString(file, body);
        return file;
    }

    private Result run(Path config, String... args) {
        return run(config, null, args);
    }

    private Result run(Path config, Path keyFile, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = DialCliFactory.build();
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        int offset = keyFile != null ? 4 : 2;
        String[] full = new String[offset + args.length];
        full[0] = "--config";
        full[1] = config.toString();
        if (keyFile != null) {
            full[2] = "--api-key-file";
            full[3] = keyFile.toString();
        }
        System.arraycopy(args, 0, full, offset, args.length);
        return new Result(cli.execute(full), out.toString(), err.toString());
    }

    private record Result(int exitCode, String out, String err) { }

    // ───── Group A: bucket-parameterization regression ─────

    @Test
    void interceptorAddUsesPlatformBucketInUrl(@TempDir Path tmp) throws Exception {
        Path config = writeProfile(tmp);
        Path body = writeFile(tmp, "spec.json", "{\"endpoint\":\"http://x\"}");
        AtomicReference<String> capturedPath = new AtomicReference<>();
        server.createContext("/v1/interceptors/platform/my-guard", exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            send(exchange, 201, "{\"name\":\"my-guard\"}");
        });

        Result r = run(config, apiKeyFile(tmp),
                "interceptor", "add", "--name", "interceptors/platform/my-guard",
                "--from-file", body.toString());

        assertEquals(0, r.exitCode, r.err);
        assertEquals("/v1/interceptors/platform/my-guard", capturedPath.get());
    }

    @Test
    void roleAddUsesPlatformBucketInUrl(@TempDir Path tmp) throws Exception {
        Path config = writeProfile(tmp);
        Path body = writeFile(tmp, "spec.json", "{\"limits\":{}}");
        AtomicReference<String> capturedPath = new AtomicReference<>();
        server.createContext("/v1/roles/platform/viewer", exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            send(exchange, 201, "{\"name\":\"viewer\"}");
        });

        Result r = run(config, apiKeyFile(tmp),
                "role", "add", "--name", "roles/platform/viewer",
                "--from-file", body.toString());

        assertEquals(0, r.exitCode, r.err);
        assertEquals("/v1/roles/platform/viewer", capturedPath.get());
    }

    @Test
    void applicationAddUsesPublicBucketInUrl(@TempDir Path tmp) throws Exception {
        Path config = writeProfile(tmp);
        Path body = writeFile(tmp, "spec.json", "{\"endpoint\":\"http://x\"}");
        AtomicReference<String> capturedPath = new AtomicReference<>();
        server.createContext("/v1/applications/public/my-app", exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            send(exchange, 201, "{\"name\":\"my-app\"}");
        });

        Result r = run(config, apiKeyFile(tmp),
                "application", "add", "--name", "applications/public/my-app",
                "--from-file", body.toString());

        assertEquals(0, r.exitCode, r.err);
        assertEquals("/v1/applications/public/my-app", capturedPath.get());
    }

    @Test
    void interceptorAddRejectsWrongBucketId(@TempDir Path tmp) throws Exception {
        Path config = writeProfile(tmp);
        Path body = writeFile(tmp, "spec.json", "{}");

        Result r = run(config, apiKeyFile(tmp),
                "interceptor", "add", "--name", "interceptors/public/my-guard",
                "--from-file", body.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("interceptors/platform/<name>"), r.err);
    }

    @Test
    void interceptorAddRejectsBareName(@TempDir Path tmp) throws Exception {
        Path config = writeProfile(tmp);
        Path body = writeFile(tmp, "spec.json", "{}");

        Result r = run(config, apiKeyFile(tmp),
                "interceptor", "add", "--name", "my-guard",
                "--from-file", body.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("canonical id"), r.err);
    }

    // ───── Group B: one write round-trip per non-model type ─────

    @Test
    void interceptorAddHappyPath(@TempDir Path tmp) throws Exception {
        Path config = writeProfile(tmp);
        Path body = writeFile(tmp, "spec.json", "{\"endpoint\":\"http://x\"}");
        respond("/v1/interceptors/platform/g1", 201, "{\"name\":\"g1\"}");

        Result r = run(config, apiKeyFile(tmp),
                "interceptor", "add", "--name", "interceptors/platform/g1",
                "--from-file", body.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("Created"), r.out);
    }

    @Test
    void roleUpdateHappyPath(@TempDir Path tmp) throws Exception {
        Path config = writeProfile(tmp);
        AtomicReference<String> putBody = new AtomicReference<>();
        server.createContext("/v1/roles/platform/viewer", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("ETag", "\"v1\"");
                send(exchange, 200, "{\"name\":\"viewer\",\"limits\":{}}");
            } else {
                putBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.getResponseHeaders().add("ETag", "\"v2\"");
                send(exchange, 200, "{\"name\":\"viewer\",\"limits\":{\"day\":1}}");
            }
        });

        Result r = run(config, apiKeyFile(tmp),
                "role", "update", "roles/platform/viewer", "--set", "limits.day=1");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("Updated"), r.out);
        assertTrue(putBody.get().contains("\"day\":1"), putBody.get());
    }

    @Test
    void keyDeleteHappyPath(@TempDir Path tmp) throws Exception {
        Path config = writeProfile(tmp);
        AtomicReference<String> capturedMethod = new AtomicReference<>();
        server.createContext("/v1/keys/platform/k1", exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            send(exchange, 204, "");
        });

        Result r = run(config, apiKeyFile(tmp),
                "key", "delete", "keys/platform/k1");

        assertEquals(0, r.exitCode, r.err);
        assertEquals("DELETE", capturedMethod.get());
        assertTrue(r.out.contains("Deleted"), r.out);
    }

    @Test
    void routeValidateHappyPath(@TempDir Path tmp) throws Exception {
        Path config = writeProfile(tmp);
        Path body = writeFile(tmp, "spec.json", "{\"paths\":[\"/x\"]}");
        AtomicReference<String> envelope = new AtomicReference<>();
        server.createContext("/v1/admin/validate", exchange -> {
            envelope.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"routes/platform/r1\",\"status\":\"valid\"}]}");
        });

        Result r = run(config, apiKeyFile(tmp),
                "route", "validate", "--name", "routes/platform/r1",
                "--from-file", body.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("Valid:"), r.out);
        assertTrue(envelope.get().contains("\"kind\":\"Route\""), envelope.get());
        assertTrue(envelope.get().contains("\"name\":\"r1\""), envelope.get());
    }

    @Test
    void schemaPromoteHappyPath(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            respond("/v1/schemas/public/s1", 200, "{\"name\":\"s1\",\"$schema\":\"x\"}");
            AtomicReference<String> applyBody = new AtomicReference<>();
            target.createContext("/v1/admin/apply", exchange -> {
                applyBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                send(exchange, 200, "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"schemas/public/s1\",\"status\":\"applied\"}]}");
            });

            Files.writeString(tmp.resolve("key.txt"), "test-key");
            Result r = run(config,
                    "schema", "promote", "--from", "dev", "--to", "uat",
                    "--name", "schemas/public/s1");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(r.out.contains("Promoted"), r.out);
            assertTrue(applyBody.get().contains("\"kind\":\"Schema\""), applyBody.get());
        } finally {
            target.stop(0);
        }
    }

    @Test
    void applicationDiffHappyPath(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            respond("/v1/applications/public/a1", 200,
                    "{\"name\":\"a1\",\"endpoint\":\"http://src\"}");
            target.createContext("/v1/applications/public/a1", exchange ->
                    send(exchange, 200, "{\"name\":\"a1\",\"endpoint\":\"http://tgt\"}"));

            Files.writeString(tmp.resolve("key.txt"), "test-key");
            Result r = run(config,
                    "application", "diff", "--source", "dev", "--target", "uat",
                    "--name", "applications/public/a1");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(r.out.contains("~"), r.out);
            assertTrue(r.out.contains("endpoint"), r.out);
        } finally {
            target.stop(0);
        }
    }

    @Test
    void toolsetAddHappyPath(@TempDir Path tmp) throws Exception {
        Path config = writeProfile(tmp);
        Path body = writeFile(tmp, "spec.json", "{\"tools\":[]}");
        AtomicReference<String> envelopeKind = new AtomicReference<>();
        respond("/v1/toolsets/public/t1", 201, "{\"name\":\"t1\"}");
        server.createContext("/v1/admin/validate", exchange -> {
            envelopeKind.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, "{\"valid\":1,\"failed\":0,\"results\":[]}");
        });

        Result added = run(config, apiKeyFile(tmp),
                "toolset", "add", "--name", "toolsets/public/t1",
                "--from-file", body.toString());
        assertEquals(0, added.exitCode, added.err);
        assertTrue(added.out.contains("Created"), added.out);

        Result validated = run(config, apiKeyFile(tmp),
                "toolset", "validate", "--name", "toolsets/public/t1",
                "--from-file", body.toString());
        assertEquals(0, validated.exitCode, validated.err);
        assertTrue(envelopeKind.get().contains("\"kind\":\"ToolSet\""), envelopeKind.get());
    }

    // ───── Group C: settings singleton special shape ─────

    @Test
    void settingsUpdateCallsPut(@TempDir Path tmp) throws Exception {
        Path config = writeProfile(tmp);
        AtomicReference<String> capturedMethod = new AtomicReference<>();
        server.createContext("/v1/settings/platform/global", exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("ETag", "\"v1\"");
                send(exchange, 200, "{\"globalInterceptors\":[]}");
            } else {
                send(exchange, 200, "{\"globalInterceptors\":[\"x\"]}");
            }
        });

        Result r = run(config, apiKeyFile(tmp),
                "settings", "update", "--set", "globalInterceptors=[\"x\"]");

        assertEquals(0, r.exitCode, r.err);
        assertEquals("PUT", capturedMethod.get());
        assertTrue(r.out.contains("Updated"), r.out);
    }

    @Test
    void settingsUpdateUsesGetMergePut(@TempDir Path tmp) throws Exception {
        Path config = writeProfile(tmp);
        AtomicReference<String> putBody = new AtomicReference<>();
        server.createContext("/v1/settings/platform/global", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("ETag", "\"v1\"");
                send(exchange, 200, "{\"globalInterceptors\":[\"a\"],\"retriableErrorCodes\":[]}");
            } else {
                putBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                send(exchange, 200, "{}");
            }
        });

        Result r = run(config, apiKeyFile(tmp),
                "settings", "update", "--set", "retriableErrorCodes=[599]");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(putBody.get().contains("\"globalInterceptors\":[\"a\"]"), putBody.get());
        assertTrue(putBody.get().contains("\"retriableErrorCodes\":[599]"), putBody.get());
    }

    @Test
    void settingsDeleteCallsDelete(@TempDir Path tmp) throws Exception {
        Path config = writeProfile(tmp);
        AtomicReference<String> capturedMethod = new AtomicReference<>();
        server.createContext("/v1/settings/platform/global", exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            send(exchange, 204, "");
        });

        Result r = run(config, apiKeyFile(tmp), "settings", "delete");

        assertEquals(0, r.exitCode, r.err);
        assertEquals("DELETE", capturedMethod.get());
        assertTrue(r.out.contains("Deleted"), r.out);
    }

    @Test
    void settingsDeleteIdempotentOn204(@TempDir Path tmp) throws Exception {
        Path config = writeProfile(tmp);
        respond("/v1/settings/platform/global", 204, "");

        Result r = run(config, apiKeyFile(tmp), "settings", "delete");

        assertEquals(0, r.exitCode, r.err);
    }

    @Test
    void settingsValidateHappyPath(@TempDir Path tmp) throws Exception {
        Path config = writeProfile(tmp);
        Path body = writeFile(tmp, "spec.json", "{\"globalInterceptors\":[]}");
        AtomicReference<String> envelope = new AtomicReference<>();
        server.createContext("/v1/admin/validate", exchange -> {
            envelope.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, "{\"valid\":1,\"failed\":0,\"results\":[]}");
        });

        Result r = run(config, apiKeyFile(tmp),
                "settings", "validate", "--from-file", body.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(envelope.get().contains("\"kind\":\"Settings\""), envelope.get());
        assertTrue(envelope.get().contains("\"name\":\"global\""), envelope.get());
    }

    @Test
    void settingsPromoteHappyPath(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            respond("/v1/settings/platform/global", 200, "{\"globalInterceptors\":[\"a\"]}");
            AtomicReference<String> applyBody = new AtomicReference<>();
            target.createContext("/v1/admin/apply", exchange -> {
                applyBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                send(exchange, 200, "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"settings/platform/global\",\"status\":\"applied\"}]}");
            });

            Files.writeString(tmp.resolve("key.txt"), "test-key");
            Result r = run(config,
                    "settings", "promote", "--from", "dev", "--to", "uat");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(r.out.contains("Promoted"), r.out);
            assertTrue(applyBody.get().contains("\"kind\":\"Settings\""), applyBody.get());
            assertTrue(applyBody.get().contains("\"name\":\"global\""), applyBody.get());
        } finally {
            target.stop(0);
        }
    }

    @Test
    void settingsDiffHappyPath(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            respond("/v1/settings/platform/global", 200, "{\"globalInterceptors\":[\"a\"]}");
            target.createContext("/v1/settings/platform/global", exchange ->
                    send(exchange, 200, "{\"globalInterceptors\":[\"b\"]}"));

            Files.writeString(tmp.resolve("key.txt"), "test-key");
            Result r = run(config,
                    "settings", "diff", "--source", "dev", "--target", "uat");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(r.out.contains("~"), r.out);
            assertTrue(r.out.contains("globalInterceptors"), r.out);
        } finally {
            target.stop(0);
        }
    }

    // ───── Group D: platform-bucket diff regression ─────

    @Test
    void roleDiffSingleEntityUsesPlatformBucketInPath(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            AtomicReference<String> targetPath = new AtomicReference<>();
            respond("/v1/roles/platform/viewer", 200, "{\"name\":\"viewer\"}");
            target.createContext("/v1/roles/platform/viewer", exchange -> {
                targetPath.set(exchange.getRequestURI().getPath());
                send(exchange, 200, "{\"name\":\"viewer\"}");
            });

            Files.writeString(tmp.resolve("key.txt"), "test-key");
            Result r = run(config,
                    "role", "diff", "--source", "dev", "--target", "uat",
                    "--name", "roles/platform/viewer");

            assertEquals(0, r.exitCode, r.err);
            assertEquals("/v1/roles/platform/viewer", targetPath.get());
        } finally {
            target.stop(0);
        }
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> send(exchange, status, body));
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0) {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}

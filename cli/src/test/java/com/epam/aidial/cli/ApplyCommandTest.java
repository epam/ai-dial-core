package com.epam.aidial.cli;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyCommandTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private Path writeProfileAndKey(Path tmp) throws Exception {
        Path key = tmp.resolve("key.txt");
        Files.writeString(key, "test-key");
        Path config = tmp.resolve("config.yaml");
        Files.writeString(config, """
                defaults: { env: dev }
                environments:
                  dev:
                    api_url: "%s"
                    auth: { type: api_key, key_env_var: NONEXISTENT_DIAL_TEST_KEY }
                """.formatted(baseUrl));
        return config;
    }

    private Path apiKeyFile(Path tmp) throws Exception {
        Path key = tmp.resolve("key.txt");
        if (!Files.exists(key)) {
            Files.writeString(key, "test-key");
        }
        return key;
    }

    private Result run(Path config, Path keyFile, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new DialCli());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        String[] full = new String[4 + args.length];
        full[0] = "--config";
        full[1] = config.toString();
        full[2] = "--api-key-file";
        full[3] = keyFile.toString();
        System.arraycopy(args, 0, full, 4, args.length);
        int code = cli.execute(full);
        return new Result(code, out.toString(), err.toString());
    }

    private record Result(int exitCode, String out, String err) { }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> send(exchange, status, body));
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void recordPost(String path, int status, String body, AtomicReference<String> sink, AtomicInteger hits) {
        server.createContext(path, exchange -> {
            hits.incrementAndGet();
            sink.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, status, body);
        });
    }

    @Test
    void applySingleDocYamlSuccess(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/gpt-4
                spec:
                  type: chat
                  endpoint: http://x
                """);
        AtomicReference<String> validateBody = new AtomicReference<>();
        AtomicInteger validateHits = new AtomicInteger();
        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger applyHits = new AtomicInteger();
        recordPost("/v1/admin/validate", 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/gpt-4\",\"status\":\"valid\"}]}",
                validateBody, validateHits);
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/gpt-4\",\"status\":\"applied\"}]}",
                applyBody, applyHits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        assertEquals(1, validateHits.get());
        assertEquals(1, applyHits.get());
        assertTrue(validateBody.get().contains("\"manifests\""), validateBody.get());
        assertTrue(validateBody.get().contains("\"kind\":\"Model\""), validateBody.get());
        assertTrue(validateBody.get().contains("\"name\":\"gpt-4\""), validateBody.get());
        assertTrue(applyBody.get().contains("\"name\":\"gpt-4\""), applyBody.get());
        assertTrue(r.out.contains("applied: 1, failed: 0"), r.out);
    }

    @Test
    void applyMultiDocYamlSuccess(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("multi.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                spec:
                  type: chat
                  endpoint: http://x
                ---
                kind: Role
                name: roles/platform/basic
                spec:
                  limits: {}
                """);
        AtomicReference<String> validateBody = new AtomicReference<>();
        AtomicInteger validateHits = new AtomicInteger();
        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger applyHits = new AtomicInteger();
        recordPost("/v1/admin/validate", 200,
                "{\"valid\":2,\"failed\":0,\"results\":["
                        + "{\"entityId\":\"models/public/m\",\"status\":\"valid\"},"
                        + "{\"entityId\":\"roles/platform/basic\",\"status\":\"valid\"}]}",
                validateBody, validateHits);
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":2,\"failed\":0,\"results\":["
                        + "{\"entityId\":\"models/public/m\",\"status\":\"applied\"},"
                        + "{\"entityId\":\"roles/platform/basic\",\"status\":\"applied\"}]}",
                applyBody, applyHits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(validateBody.get().contains("\"kind\":\"Model\""), validateBody.get());
        assertTrue(validateBody.get().contains("\"kind\":\"Role\""), validateBody.get());
        assertTrue(validateBody.get().contains("\"name\":\"m\""), validateBody.get());
        assertTrue(validateBody.get().contains("\"name\":\"basic\""), validateBody.get());
        assertTrue(r.out.contains("applied: 2, failed: 0"), r.out);
    }

    @Test
    void applyJsonFileSingleObject(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.json");
        Files.writeString(manifest, "{\"kind\":\"Interceptor\",\"name\":\"interceptors/platform/i1\",\"spec\":{\"endpoint\":\"http://i\"}}");
        respond("/v1/admin/validate", 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"interceptors/platform/i1\",\"status\":\"valid\"}]}");
        respond("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"interceptors/platform/i1\",\"status\":\"applied\"}]}");

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("applied: 1, failed: 0"), r.out);
    }

    @Test
    void applyJsonFileArray(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.json");
        Files.writeString(manifest, """
                [
                  {"kind":"Interceptor","name":"interceptors/platform/i1","spec":{"endpoint":"http://i"}},
                  {"kind":"Role","name":"roles/platform/basic","spec":{}}
                ]
                """);
        respond("/v1/admin/validate", 200,
                "{\"valid\":2,\"failed\":0,\"results\":["
                        + "{\"entityId\":\"interceptors/platform/i1\",\"status\":\"valid\"},"
                        + "{\"entityId\":\"roles/platform/basic\",\"status\":\"valid\"}]}");
        respond("/v1/admin/apply", 200,
                "{\"applied\":2,\"failed\":0,\"results\":["
                        + "{\"entityId\":\"interceptors/platform/i1\",\"status\":\"applied\"},"
                        + "{\"entityId\":\"roles/platform/basic\",\"status\":\"applied\"}]}");

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("applied: 2, failed: 0"), r.out);
    }

    @Test
    void applyDryRunPrintsEnvelopeNoCall(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/gpt-4
                spec: { type: chat, endpoint: http://x }
                """);
        AtomicInteger anyHits = new AtomicInteger();
        server.createContext("/", exchange -> {
            anyHits.incrementAndGet();
            send(exchange, 500, "{}");
        });

        Result r = run(config, apiKeyFile(tmp), "--dry-run", "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        assertEquals(0, anyHits.get(), "dry-run must not call the server");
        assertTrue(r.out.contains("\"manifests\""), r.out);
        assertTrue(r.out.contains("\"name\":\"gpt-4\""), r.out);
        assertTrue(r.out.contains("\"precheck\":true"), r.out);
    }

    @Test
    void applyValidateFailureBlocksApply(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                spec: { type: chat, interceptors: [missing] }
                """);
        respond("/v1/admin/validate", 422,
                "{\"valid\":0,\"failed\":1,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"FAILED\",\"error\":\"interceptor 'missing' not found\"}]}");
        AtomicInteger applyHits = new AtomicInteger();
        server.createContext("/v1/admin/apply", exchange -> {
            applyHits.incrementAndGet();
            send(exchange, 200, "{}");
        });

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertEquals(0, applyHits.get(), "validate failure must block apply");
        assertTrue(r.err.contains("interceptor 'missing' not found"), r.err);
        assertTrue(r.err.contains("FAILED"), r.err);
    }

    @Test
    void applyValidate200WithFailedExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                spec: { type: chat }
                """);
        respond("/v1/admin/validate", 200,
                "{\"valid\":0,\"failed\":1,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"FAILED\",\"error\":\"missing endpoint\"}]}");
        AtomicInteger applyHits = new AtomicInteger();
        server.createContext("/v1/admin/apply", exchange -> {
            applyHits.incrementAndGet();
            send(exchange, 200, "{}");
        });

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertEquals(0, applyHits.get());
        assertTrue(r.err.contains("missing endpoint"), r.err);
    }

    @Test
    void applyValidateSkippedNotPrintedAsFailure(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                spec: { type: chat }
                ---
                kind: Role
                name: roles/platform/basic
                spec: {}
                """);
        respond("/v1/admin/validate", 422,
                "{\"valid\":1,\"failed\":1,\"results\":["
                        + "{\"entityId\":\"models/public/m\",\"status\":\"FAILED\",\"error\":\"bad endpoint\"},"
                        + "{\"entityId\":\"roles/platform/basic\",\"status\":\"skipped\"}"
                        + "]}");

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("bad endpoint"), r.err);
        assertFalse(r.err.contains("basic"), "skipped entries must not be printed as failures: " + r.err);
    }

    @Test
    void applyApplyTimeFailureExitsOne(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                spec: { type: chat, endpoint: http://x }
                """);
        respond("/v1/admin/validate", 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}");
        respond("/v1/admin/apply", 200,
                "{\"applied\":0,\"failed\":1,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"FAILED\",\"error\":\"storage write failed\"}]}");

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(1, r.exitCode);
        assertTrue(r.err.contains("storage write failed"), r.err);
        assertTrue(r.out.contains("applied: 0, failed: 1"), r.out);
    }

    @Test
    void applyAppliedInvalidWarningsExitZero(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                spec: { type: chat, endpoint: http://x }
                """);
        respond("/v1/admin/validate", 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}");
        respond("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied_invalid\",\"error\":\"interceptor missing\"}]}");

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.err.contains("warn"), r.err);
        assertTrue(r.err.contains("interceptor missing"), r.err);
        assertTrue(r.out.contains("applied: 1, failed: 0"), r.out);
    }

    @Test
    void applyValidate403ExitsThree(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                spec: { type: chat, endpoint: http://x }
                """);
        respond("/v1/admin/validate", 403, "{\"error\":\"forbidden\"}");

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(3, r.exitCode);
        assertTrue(r.err.contains("403"), r.err);
    }

    @Test
    void applyApply422ExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                spec: { type: chat, endpoint: http://x }
                """);
        AtomicInteger applyHits = new AtomicInteger();
        AtomicReference<String> applyBody = new AtomicReference<>();
        respond("/v1/admin/validate", 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}");
        recordPost("/v1/admin/apply", 422,
                "{\"applied\":0,\"failed\":1,\"results\":[{\"entityId\":\"models/public/m\","
                        + "\"status\":\"FAILED\",\"error\":\"server precheck rejected post-validate race\"}]}",
                applyBody, applyHits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertEquals(1, applyHits.get());
        assertTrue(r.err.contains("server precheck rejected"), r.err);
    }

    @Test
    void applyApplyNetworkErrorExitsOne(@TempDir Path tmp) throws Exception {
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                spec: { type: chat, endpoint: http://x }
                """);
        Path badConfig = tmp.resolve("bad.yaml");
        Files.writeString(badConfig, """
                defaults: { env: dev }
                environments:
                  dev:
                    api_url: "http://127.0.0.1:1"
                    auth: { type: api_key, key_env_var: NONE }
                """);

        Result r = run(badConfig, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(1, r.exitCode);
    }

    @Test
    void applyUnknownKindRejectedAtParse(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Foo
                name: foo/public/x
                spec: {}
                """);
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            send(exchange, 500, "{}");
        });

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertEquals(0, hits.get(), "parse error must not call the server");
        assertTrue(r.err.contains("Foo"), r.err);
    }

    @Test
    void applyBundleKindRejectedAtParse(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Bundle
                name: onboard-x
                spec: {}
                """);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("Bundle"), r.err);
        assertTrue(r.err.toLowerCase().contains("not supported") || r.err.toLowerCase().contains("deferred"),
                "expected deferred-feature message, got: " + r.err);
    }

    @Test
    void applyTemplateFieldUnknownTemplateExitsTwo(@TempDir Path tmp) throws Exception {
        // 4C.1: 'template:' is accepted but must reference a known template. When the profile
        // has no 'templates' block, an unknown template name surfaces as a TemplateException.
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                template: bedrock-chat
                spec: { type: chat, endpoint: http://x }
                """);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("bedrock-chat"), r.err);
    }

    @Test
    void applyOverlayKindRejectedAsDeferred(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: ModelOverlay
                target: models/public/m
                patch: { pricing: { prompt: 0.001 } }
                """);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("ModelOverlay") || r.err.contains("Overlay") || r.err.contains("patch")
                        || r.err.contains("target"),
                r.err);
    }

    @Test
    void applyMissingFile(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("missing.yaml");

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.toLowerCase().contains("not found") || r.err.toLowerCase().contains("no such"), r.err);
    }

    @Test
    void applyMalformedYaml(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, "kind: Model\n  bad-indent: [unclosed");

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
    }

    @Test
    void applyEmptyManifestsExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("empty.yaml");
        Files.writeString(manifest, "");

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.toLowerCase().contains("no manifest"), r.err);
    }

    @Test
    void applyMissingNameForModelExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                spec: { type: chat, endpoint: http://x }
                """);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.toLowerCase().contains("name"), r.err);
    }

    @Test
    void applySettingsRequiresGlobalName(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Settings
                name: not-global
                spec: { globalInterceptors: [] }
                """);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.toLowerCase().contains("global"), r.err);
    }

    @Test
    void applySettingsGlobalNameAccepted(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Settings
                name: global
                spec: { globalInterceptors: [] }
                """);
        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger applyHits = new AtomicInteger();
        respond("/v1/admin/validate", 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"settings/platform/global\",\"status\":\"valid\"}]}");
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"settings/platform/global\",\"status\":\"applied\"}]}",
                applyBody, applyHits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(applyBody.get().contains("\"kind\":\"Settings\""), applyBody.get());
        assertTrue(applyBody.get().contains("\"name\":\"global\""), applyBody.get());
    }

    @Test
    void applyCanonicalNameStrippedToSimpleOnWire(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Key
                name: keys/platform/proxyKey1
                spec: { project: P, roles: [basic], key: secret }
                """);
        AtomicReference<String> validateBody = new AtomicReference<>();
        AtomicInteger validateHits = new AtomicInteger();
        recordPost("/v1/admin/validate", 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"keys/platform/proxyKey1\",\"status\":\"valid\"}]}",
                validateBody, validateHits);
        respond("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"keys/platform/proxyKey1\",\"status\":\"applied\"}]}");

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(validateBody.get().contains("\"name\":\"proxyKey1\""), validateBody.get());
        assertFalse(validateBody.get().contains("\"name\":\"keys/platform/proxyKey1\""), validateBody.get());
    }

    @Test
    void applyWrongBucketCanonicalRejected(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/platform/wrong-bucket
                spec: { type: chat, endpoint: http://x }
                """);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("models/public/"), r.err);
    }

    @Test
    void applyDirectoryRecursivelyAggregatesManifests(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path root = tmp.resolve("manifests");
        Path subdir = root.resolve("nested");
        Files.createDirectories(subdir);
        Files.writeString(root.resolve("models.yaml"), """
                kind: Model
                name: models/public/m1
                spec: { type: chat, endpoint: http://x }
                ---
                kind: Model
                name: models/public/m2
                spec: { type: chat, endpoint: http://y }
                """);
        Files.writeString(subdir.resolve("roles.yaml"), """
                kind: Role
                name: roles/platform/basic
                spec: { limits: {} }
                """);
        AtomicReference<String> validateBody = new AtomicReference<>();
        AtomicInteger validateHits = new AtomicInteger();
        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger applyHits = new AtomicInteger();
        recordPost("/v1/admin/validate", 200,
                "{\"valid\":3,\"failed\":0,\"results\":["
                        + "{\"entityId\":\"models/public/m1\",\"status\":\"valid\"},"
                        + "{\"entityId\":\"models/public/m2\",\"status\":\"valid\"},"
                        + "{\"entityId\":\"roles/platform/basic\",\"status\":\"valid\"}]}",
                validateBody, validateHits);
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":3,\"failed\":0,\"results\":["
                        + "{\"entityId\":\"models/public/m1\",\"status\":\"applied\"},"
                        + "{\"entityId\":\"models/public/m2\",\"status\":\"applied\"},"
                        + "{\"entityId\":\"roles/platform/basic\",\"status\":\"applied\"}]}",
                applyBody, applyHits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", root.toString());

        assertEquals(0, r.exitCode, r.err);
        assertEquals(1, validateHits.get());
        assertEquals(1, applyHits.get());
        assertTrue(validateBody.get().contains("\"name\":\"m1\""), validateBody.get());
        assertTrue(validateBody.get().contains("\"name\":\"m2\""), validateBody.get());
        assertTrue(validateBody.get().contains("\"name\":\"basic\""), validateBody.get());
        assertTrue(applyBody.get().contains("\"name\":\"basic\""), applyBody.get());
        assertTrue(r.out.contains("applied: 3, failed: 0"), r.out);
    }

    @Test
    void applyDirectoryMixesYamlYmlJson(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path root = tmp.resolve("manifests");
        Files.createDirectories(root);
        Files.writeString(root.resolve("a.yaml"), """
                kind: Model
                name: models/public/a
                spec: { type: chat, endpoint: http://a }
                """);
        Files.writeString(root.resolve("b.yml"), """
                kind: Model
                name: models/public/b
                spec: { type: chat, endpoint: http://b }
                """);
        Files.writeString(root.resolve("c.json"), """
                {"kind":"Role","name":"roles/platform/basic","spec":{"limits":{}}}
                """);
        AtomicReference<String> validateBody = new AtomicReference<>();
        AtomicInteger validateHits = new AtomicInteger();
        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger applyHits = new AtomicInteger();
        recordPost("/v1/admin/validate", 200,
                "{\"valid\":3,\"failed\":0,\"results\":[]}", validateBody, validateHits);
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":3,\"failed\":0,\"results\":[]}", applyBody, applyHits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", root.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(applyBody.get().contains("\"name\":\"a\""), applyBody.get());
        assertTrue(applyBody.get().contains("\"name\":\"b\""), applyBody.get());
        assertTrue(applyBody.get().contains("\"name\":\"basic\""), applyBody.get());
    }

    @Test
    void applyDirectoryDeterministicOrder(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path root = tmp.resolve("manifests");
        Files.createDirectories(root);
        Files.writeString(root.resolve("c.yaml"), "kind: Model\nname: models/public/c\nspec: { type: chat, endpoint: http://c }\n");
        Files.writeString(root.resolve("a.yaml"), "kind: Model\nname: models/public/a\nspec: { type: chat, endpoint: http://a }\n");
        Files.writeString(root.resolve("b.yaml"), "kind: Model\nname: models/public/b\nspec: { type: chat, endpoint: http://b }\n");
        AtomicInteger anyHits = new AtomicInteger();
        server.createContext("/", exchange -> {
            anyHits.incrementAndGet();
            send(exchange, 500, "{}");
        });

        Result r = run(config, apiKeyFile(tmp), "--dry-run", "apply", "-f", root.toString());

        assertEquals(0, r.exitCode, r.err);
        assertEquals(0, anyHits.get());
        int posA = r.out.indexOf("\"name\":\"a\"");
        int posB = r.out.indexOf("\"name\":\"b\"");
        int posC = r.out.indexOf("\"name\":\"c\"");
        assertTrue(posA >= 0 && posB >= 0 && posC >= 0, r.out);
        assertTrue(posA < posB && posB < posC, "expected a < b < c in payload, got: " + r.out);
    }

    @Test
    void applyDirectorySkipsNonManifestExtensions(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path root = tmp.resolve("manifests");
        Files.createDirectories(root);
        Files.writeString(root.resolve("model.yaml"), "kind: Model\nname: models/public/m\nspec: { type: chat, endpoint: http://x }\n");
        Files.writeString(root.resolve("README.md"), "# notes");
        Files.writeString(root.resolve("model.yaml.bak"), "garbage: !!! not yaml");
        Files.writeString(root.resolve("notes.txt"), "hello");
        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger applyHits = new AtomicInteger();
        recordPost("/v1/admin/validate", 200,
                "{\"valid\":1,\"failed\":0,\"results\":[]}", new AtomicReference<>(), new AtomicInteger());
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[]}", applyBody, applyHits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", root.toString());

        assertEquals(0, r.exitCode, r.err);
        assertEquals(1, applyHits.get());
        assertTrue(applyBody.get().contains("\"name\":\"m\""), applyBody.get());
        assertFalse(applyBody.get().contains("README"), applyBody.get());
        assertFalse(applyBody.get().contains("garbage"), applyBody.get());
    }

    @Test
    void applyDirectorySkipsHiddenPaths(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path root = tmp.resolve("manifests");
        Path hidden = root.resolve(".git");
        Files.createDirectories(hidden);
        Files.writeString(root.resolve("model.yaml"), "kind: Model\nname: models/public/m\nspec: { type: chat, endpoint: http://x }\n");
        Files.writeString(hidden.resolve("staged.yaml"), "kind: Model\nname: models/public/should-skip\nspec: { type: chat, endpoint: http://x }\n");
        Files.writeString(root.resolve(".hidden.yaml"), "kind: Model\nname: models/public/should-skip-2\nspec: { type: chat, endpoint: http://x }\n");
        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger applyHits = new AtomicInteger();
        recordPost("/v1/admin/validate", 200,
                "{\"valid\":1,\"failed\":0,\"results\":[]}", new AtomicReference<>(), new AtomicInteger());
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[]}", applyBody, applyHits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", root.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(applyBody.get().contains("\"name\":\"m\""), applyBody.get());
        assertFalse(applyBody.get().contains("should-skip"), applyBody.get());
    }

    @Test
    void applyDirectoryEmptyExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path root = tmp.resolve("empty");
        Files.createDirectories(root);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", root.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("No manifests found"), r.err);
    }

    @Test
    void applyDirectoryDoesNotLoadDotDisableMarkers(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path root = tmp.resolve("manifests");
        Files.createDirectories(root);
        Files.writeString(root.resolve("model.yaml"), "kind: Model\nname: models/public/m\nspec: { type: chat, endpoint: http://x }\n");
        Files.writeString(root.resolve("model.disable"), "");
        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger applyHits = new AtomicInteger();
        recordPost("/v1/admin/validate", 200,
                "{\"valid\":1,\"failed\":0,\"results\":[]}", new AtomicReference<>(), new AtomicInteger());
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[]}", applyBody, applyHits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", root.toString());

        assertEquals(0, r.exitCode, r.err);
        assertEquals(1, applyHits.get());
    }

    @Test
    void applyDirectoryFileWithParseErrorAttributesPath(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path root = tmp.resolve("manifests");
        Files.createDirectories(root);
        Files.writeString(root.resolve("good.yaml"), "kind: Model\nname: models/public/g\nspec: { type: chat, endpoint: http://x }\n");
        Files.writeString(root.resolve("bad.yaml"), "kind: Model\n  bad-indent: [unclosed");

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", root.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("bad.yaml"), r.err);
    }

    @Test
    void applyOverlayPatchesSpec(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        Files.createDirectories(baseRoot.resolve("models"));
        Files.writeString(baseRoot.resolve("models/m.yaml"),
                "kind: Model\nname: models/public/m\nspec:\n  type: chat\n  endpoint: http://base\n");
        Files.createDirectories(overlayRoot.resolve("models"));
        Files.writeString(overlayRoot.resolve("models/m.yaml"), """
                kind: ModelOverlay
                target: models/public/m
                patch:
                  endpoint: http://patched
                """);
        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger applyHits = new AtomicInteger();
        recordPost("/v1/admin/validate", 200,
                "{\"valid\":1,\"failed\":0,\"results\":[]}", new AtomicReference<>(), new AtomicInteger());
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[]}", applyBody, applyHits);

        Result r = run(config, apiKeyFile(tmp), "apply",
                "-f", baseRoot.toString(),
                "--overlay", overlayRoot.toString());

        assertEquals(0, r.exitCode, r.err);
        assertEquals(1, applyHits.get());
        assertTrue(applyBody.get().contains("\"endpoint\":\"http://patched\""), applyBody.get());
        assertTrue(applyBody.get().contains("\"type\":\"chat\""), applyBody.get());
    }

    @Test
    void applyOverlayDisableMarkerRemovesEntity(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        Files.createDirectories(baseRoot.resolve("models"));
        Files.writeString(baseRoot.resolve("models/keep.yaml"),
                "kind: Model\nname: models/public/keep\nspec: { type: chat, endpoint: http://k }\n");
        Files.writeString(baseRoot.resolve("models/drop.yaml"),
                "kind: Model\nname: models/public/drop\nspec: { type: chat, endpoint: http://d }\n");
        Files.createDirectories(overlayRoot.resolve("models"));
        Files.writeString(overlayRoot.resolve("models/drop.disable"), "");
        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger applyHits = new AtomicInteger();
        recordPost("/v1/admin/validate", 200,
                "{\"valid\":1,\"failed\":0,\"results\":[]}", new AtomicReference<>(), new AtomicInteger());
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[]}", applyBody, applyHits);

        Result r = run(config, apiKeyFile(tmp), "apply",
                "-f", baseRoot.toString(),
                "--overlay", overlayRoot.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(applyBody.get().contains("\"name\":\"keep\""), applyBody.get());
        assertFalse(applyBody.get().contains("\"name\":\"drop\""), applyBody.get());
    }

    @Test
    void applyOverlayMissingTargetExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        Files.createDirectories(baseRoot.resolve("models"));
        Files.writeString(baseRoot.resolve("models/m.yaml"),
                "kind: Model\nname: models/public/m\nspec: { type: chat, endpoint: http://x }\n");
        Files.createDirectories(overlayRoot);
        Files.writeString(overlayRoot.resolve("ghost.yaml"), """
                kind: ModelOverlay
                target: models/public/ghost
                patch:
                  endpoint: http://y
                """);

        Result r = run(config, apiKeyFile(tmp), "apply",
                "-f", baseRoot.toString(),
                "--overlay", overlayRoot.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("matches no base manifest"), r.err);
    }

    @Test
    void applyOverlayDisableWithSingleFileBaseExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path baseFile = tmp.resolve("m.yaml");
        Files.writeString(baseFile, "kind: Model\nname: models/public/m\nspec: { type: chat, endpoint: http://x }\n");
        Path overlayRoot = tmp.resolve("overlay");
        Files.createDirectories(overlayRoot);
        Files.writeString(overlayRoot.resolve("m.disable"), "");

        Result r = run(config, apiKeyFile(tmp), "apply",
                "-f", baseFile.toString(),
                "--overlay", overlayRoot.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains(".disable") && r.err.contains("-f"), r.err);
    }

    @Test
    void applyOverlayNullDeletesFieldEndToEnd(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        Files.createDirectories(baseRoot.resolve("models"));
        Files.writeString(baseRoot.resolve("models/m.yaml"),
                "kind: Model\nname: models/public/m\nspec:\n  type: chat\n  endpoint: http://base\n");
        Files.createDirectories(overlayRoot.resolve("models"));
        Files.writeString(overlayRoot.resolve("models/m.yaml"), """
                kind: ModelOverlay
                target: models/public/m
                patch:
                  type: null
                """);
        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger applyHits = new AtomicInteger();
        recordPost("/v1/admin/validate", 200,
                "{\"valid\":1,\"failed\":0,\"results\":[]}", new AtomicReference<>(), new AtomicInteger());
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[]}", applyBody, applyHits);

        Result r = run(config, apiKeyFile(tmp), "apply",
                "-f", baseRoot.toString(),
                "--overlay", overlayRoot.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(applyBody.get().contains("\"endpoint\":\"http://base\""), applyBody.get());
        assertFalse(applyBody.get().contains("\"type\""), "RFC 7396 null-delete must remove field end-to-end: "
                + applyBody.get());
    }

    @Test
    void applyOverlayParamsOverrideFlowsThroughTemplate(@TempDir Path tmp) throws Exception {
        // Overlay overrides params consumed by a ${params.X} substitution in the manifest spec.
        Path config = writeProfileAndKey(tmp);
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        Files.createDirectories(baseRoot.resolve("models"));
        Files.writeString(baseRoot.resolve("models/m.yaml"), """
                kind: Model
                name: models/public/m
                params:
                  region: us-east-1
                spec:
                  type: chat
                  endpoint: "http://api-${params.region}.example"
                """);
        Files.createDirectories(overlayRoot.resolve("models"));
        Files.writeString(overlayRoot.resolve("models/m.yaml"), """
                kind: ModelOverlay
                target: models/public/m
                params:
                  region: us-west-2
                """);
        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger applyHits = new AtomicInteger();
        recordPost("/v1/admin/validate", 200,
                "{\"valid\":1,\"failed\":0,\"results\":[]}", new AtomicReference<>(), new AtomicInteger());
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[]}", applyBody, applyHits);

        Result r = run(config, apiKeyFile(tmp), "apply",
                "-f", baseRoot.toString(),
                "--overlay", overlayRoot.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(applyBody.get().contains("\"endpoint\":\"http://api-us-west-2.example\""),
                "overlay params must reach template resolution: " + applyBody.get());
    }

    @Test
    void applyOverlayAllDisabledExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        Files.createDirectories(baseRoot.resolve("models"));
        Files.writeString(baseRoot.resolve("models/m.yaml"),
                "kind: Model\nname: models/public/m\nspec: { type: chat, endpoint: http://x }\n");
        Files.createDirectories(overlayRoot.resolve("models"));
        Files.writeString(overlayRoot.resolve("models/m.disable"), "");

        Result r = run(config, apiKeyFile(tmp), "apply",
                "-f", baseRoot.toString(),
                "--overlay", overlayRoot.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("No manifests remain"), r.err);
    }
}

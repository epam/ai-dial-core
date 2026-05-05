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
    void applyTemplateFieldRejectedAsDeferred(@TempDir Path tmp) throws Exception {
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
        assertTrue(r.err.contains("template"), r.err);
        assertTrue(r.err.toLowerCase().contains("not supported") || r.err.toLowerCase().contains("deferred"),
                "expected deferred-feature message, got: " + r.err);
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
}

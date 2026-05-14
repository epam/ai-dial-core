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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCommandTest {

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

    @Test
    void modelGetTableHappyPath(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        respond("/v1/models/public/gpt-4", 200,
                "{\"name\":\"gpt-4\",\"endpoint\":\"https://example/openai/gpt-4\"}");

        Result r = run(config, apiKeyFile(tmp), "model", "get", "gpt-4");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("NAME"), r.out);
        assertTrue(r.out.contains("ENDPOINT"), r.out);
        assertTrue(r.out.contains("gpt-4"), r.out);
        assertTrue(r.out.contains("https://example/openai/gpt-4"), r.out);
    }

    @Test
    void modelGetJsonOutput(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        respond("/v1/models/public/gpt-4", 200, "{\"name\":\"gpt-4\"}");

        Result r = run(config, apiKeyFile(tmp), "-o", "json", "model", "get", "gpt-4");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("\"name\""), r.out);
        assertTrue(r.out.contains("\"gpt-4\""), r.out);
    }

    @Test
    void modelGetYamlOutput(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        respond("/v1/models/public/gpt-4", 200, "{\"name\":\"gpt-4\"}");

        Result r = run(config, apiKeyFile(tmp), "-o", "yaml", "model", "get", "gpt-4");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("name: \"gpt-4\""), r.out);
    }

    @Test
    void modelGetOutputFlagAfterSubcommand(@TempDir Path tmp) throws Exception {
        // Cli.3 (2026-05-08): global flags carry ScopeType.INHERIT so `-o yaml` placed after
        // the `model get` subcommand chain binds correctly. Pre-Cli.3 the option was ignored
        // (or rejected as unknown by the leaf subcommand).
        Path config = writeProfileAndKey(tmp);
        respond("/v1/models/public/gpt-4", 200, "{\"name\":\"gpt-4\"}");

        Result r = run(config, apiKeyFile(tmp), "model", "get", "gpt-4", "-o", "yaml");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("name: \"gpt-4\""), r.out);
    }

    @Test
    void modelGetCanonicalIdPassesThrough(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        respond("/v1/models/public/gpt-4", 200, "{\"name\":\"gpt-4\",\"endpoint\":\"https://e\"}");

        Result r = run(config, apiKeyFile(tmp), "model", "get", "models/public/gpt-4");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("gpt-4"), r.out);
    }

    @Test
    void modelGet404ExitsFour(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        respond("/v1/models/public/missing", 404, "{\"error\":\"not found\"}");

        Result r = run(config, apiKeyFile(tmp), "model", "get", "missing");

        assertEquals(4, r.exitCode);
        assertTrue(r.err.contains("Not found"), r.err);
    }

    @Test
    void modelGet401ExitsThree(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        respond("/v1/models/public/x", 401, "{\"error\":\"unauthorized\"}");

        Result r = run(config, apiKeyFile(tmp), "model", "get", "x");

        assertEquals(3, r.exitCode);
        assertTrue(r.err.contains("401"), r.err);
    }

    @Test
    void modelListTableHappyPath(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        respond("/v1/models/public/", 200, """
                {"items":[
                  {"name":"gpt-4","endpoint":"https://e1"},
                  {"name":"claude-sonnet","endpoint":"https://e2"}
                ],"hasMore":false}
                """);

        Result r = run(config, apiKeyFile(tmp), "model", "list");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("gpt-4"), r.out);
        assertTrue(r.out.contains("claude-sonnet"), r.out);
        assertTrue(r.out.contains("https://e1"), r.out);
    }

    @Test
    void modelListJsonOutput(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        respond("/v1/models/public/", 200,
                "{\"items\":[{\"name\":\"gpt-4\"}],\"hasMore\":false}");

        Result r = run(config, apiKeyFile(tmp), "-o", "json", "model", "list");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("\"name\""), r.out);
        assertTrue(r.out.contains("\"gpt-4\""), r.out);
    }

    @Test
    void noEnvSelectedExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config.yaml");
        Files.writeString(config, "environments: { dev: { api_url: \"http://x\" } }\n");

        Result r = run(config, apiKeyFile(tmp), "model", "get", "gpt-4");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("No environment selected"), r.err);
    }

    @Test
    void unknownEnvExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config.yaml");
        Files.writeString(config, "defaults: { env: ghost }\nenvironments: { dev: { api_url: \"http://x\" } }\n");

        Result r = run(config, apiKeyFile(tmp), "model", "get", "gpt-4");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("'ghost' not found"), r.err);
    }

    @Test
    void getModelsAliasDispatchesToList(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        respond("/v1/models/public/", 200,
                "{\"items\":[{\"name\":\"gpt-4\",\"endpoint\":\"https://e\"}],\"hasMore\":false}");

        Result r = run(config, apiKeyFile(tmp), "get", "models");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("gpt-4"), r.out);
    }

    @Test
    void getRequiresResourceType(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);

        Result r = run(config, apiKeyFile(tmp), "get");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("Resource type required"), r.err);
    }

    @Test
    void modelGetRejectsAmbiguousPartialCanonicalId(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);

        Result r = run(config, apiKeyFile(tmp), "model", "get", "public/gpt-4");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("Ambiguous"), r.err);
        assertTrue(r.err.contains("models/public/<name>"), r.err);
    }

    @Test
    void modelListEmptyItems(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        respond("/v1/models/public/", 200, "{\"items\":[],\"hasMore\":false}");

        Result r = run(config, apiKeyFile(tmp), "model", "list");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("NAME"), r.out);
        assertTrue(r.out.contains("ENDPOINT"), r.out);
    }

    @Test
    void modelListSendsLimitQueryParam(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        java.util.concurrent.atomic.AtomicReference<String> capturedQuery = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v1/models/public/", exchange -> {
            capturedQuery.set(exchange.getRequestURI().getQuery());
            send(exchange, 200, "{\"items\":[],\"hasMore\":false}");
        });

        Result r = run(config, apiKeyFile(tmp), "model", "list");

        assertEquals(0, r.exitCode, r.err);
        assertEquals("limit=100", capturedQuery.get());
    }

    @Test
    void getRejectsUnknownResourceType(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);

        Result r = run(config, apiKeyFile(tmp), "get", "frobnicators");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("Unsupported resource type"), r.err);
    }

    @Test
    void modelAdd201HappyPath(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("model.json");
        Files.writeString(body, "{\"type\":\"chat\",\"endpoint\":\"http://x\"}");
        java.util.concurrent.atomic.AtomicReference<String> capturedBody = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v1/models/public/new-model", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("ETag", "\"e1\"");
            send(exchange, 201, "{\"name\":\"new-model\"}");
        });

        Result r = run(config, apiKeyFile(tmp),
                "model", "add", "--name", "models/public/new-model", "--from-file", body.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("Created models/public/new-model"), r.out);
        assertEquals("{\"type\":\"chat\",\"endpoint\":\"http://x\"}", capturedBody.get());
    }

    @Test
    void modelAddDryRunPrintsBodyAndDoesNotPost(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("model.json");
        Files.writeString(body, "{\"type\":\"chat\",\"endpoint\":\"http://x\"}");
        java.util.concurrent.atomic.AtomicBoolean hit = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/v1/models/public/new-model", exchange -> {
            hit.set(true);
            send(exchange, 500, "{}");
        });

        Result r = run(config, apiKeyFile(tmp), "--dry-run",
                "model", "add", "--name", "models/public/new-model", "--from-file", body.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("\"endpoint\":\"http://x\""), r.out);
        assertTrue(!hit.get(), "Server should not be called on --dry-run");
    }

    @Test
    void modelAdd409ExitsFive(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.json");
        Files.writeString(body, "{\"type\":\"chat\",\"endpoint\":\"http://x\"}");
        respond("/v1/models/public/dup", 409, "{\"error\":\"exists\"}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "add", "--name", "models/public/dup", "--from-file", body.toString());

        assertEquals(5, r.exitCode);
        assertTrue(r.err.contains("Already exists"), r.err);
    }

    @Test
    void modelAdd400ExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.json");
        Files.writeString(body, "{\"type\":\"chat\",\"upstreams\":[{\"endpoint\":\"http://x\",\"key\":\"***\"}]}");
        respond("/v1/models/public/sentinel", 400, "{\"error\":\"sentinel\"}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "add", "--name", "models/public/sentinel", "--from-file", body.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("400"), r.err);
    }

    @Test
    void modelAdd401ExitsThree(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.json");
        Files.writeString(body, "{\"type\":\"chat\"}");
        respond("/v1/models/public/x", 401, "{}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "add", "--name", "models/public/x", "--from-file", body.toString());

        assertEquals(3, r.exitCode);
    }

    @Test
    void modelAdd403ExitsThree(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.json");
        Files.writeString(body, "{\"type\":\"chat\"}");
        respond("/v1/models/public/x", 403, "{\"error\":\"forbidden\"}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "add", "--name", "models/public/x", "--from-file", body.toString());

        assertEquals(3, r.exitCode);
        assertTrue(r.err.contains("403"), r.err);
    }

    @Test
    void modelAdd500ExitsOne(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.json");
        Files.writeString(body, "{\"type\":\"chat\"}");
        respond("/v1/models/public/x", 500, "{\"error\":\"internal\"}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "add", "--name", "models/public/x", "--from-file", body.toString());

        assertEquals(1, r.exitCode);
        assertTrue(r.err.contains("500"), r.err);
    }

    @Test
    void modelAddYamlFromFileSendsJson(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("model.yaml");
        Files.writeString(body, "type: chat\nendpoint: http://yaml-host\n");
        java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> contentType = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v1/models/public/yaml-m", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            send(exchange, 201, "{\"name\":\"yaml-m\"}");
        });

        Result r = run(config, apiKeyFile(tmp),
                "model", "add", "--name", "models/public/yaml-m", "--from-file", body.toString());

        assertEquals(0, r.exitCode, r.err);
        assertEquals("application/json", contentType.get());
        assertTrue(captured.get().contains("\"type\":\"chat\""), captured.get());
        assertTrue(captured.get().contains("\"endpoint\":\"http://yaml-host\""), captured.get());
        assertTrue(!captured.get().contains("type: chat"), "Wire body must be JSON, not YAML");
    }

    @Test
    void modelAddRejectsSimpleName(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.json");
        Files.writeString(body, "{}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "add", "--name", "gpt-4", "--from-file", body.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("canonical id"), r.err);
        assertTrue(r.err.contains("models/public/<name>"), r.err);
    }

    @Test
    void modelAddRequiresName(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.json");
        Files.writeString(body, "{}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "add", "--from-file", body.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("--name"), r.err);
    }

    @Test
    void modelAddRequiresFromFile(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);

        Result r = run(config, apiKeyFile(tmp),
                "model", "add", "--name", "models/public/x");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("--from-file"), r.err);
    }

    @Test
    void modelAddFileNotFoundExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);

        Result r = run(config, apiKeyFile(tmp),
                "model", "add", "--name", "models/public/x", "--from-file", tmp.resolve("missing.json").toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("File not found"), r.err);
    }

    @Test
    void modelAddInvalidJsonFromFileExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("bad.json");
        Files.writeString(body, "{not json");

        Result r = run(config, apiKeyFile(tmp),
                "model", "add", "--name", "models/public/x", "--from-file", body.toString());

        assertEquals(2, r.exitCode);
    }

    @Test
    void modelAddAcceptsManifestEnvelope(@TempDir Path tmp) throws Exception {
        // sample/dial-cli/manifests/06-model.yaml ships a {kind: Model, name, spec} envelope —
        // make sure --from-file unwraps that shape and posts only the spec body.
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("envelope.yaml");
        Files.writeString(body, """
                kind: Model
                name: models/public/new-model
                spec:
                  type: chat
                  endpoint: "http://x"
                """);
        java.util.concurrent.atomic.AtomicReference<String> capturedBody = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v1/models/public/new-model", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 201, "{\"name\":\"new-model\"}");
        });

        Result r = run(config, apiKeyFile(tmp),
                "model", "add", "--name", "models/public/new-model", "--from-file", body.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(capturedBody.get().contains("\"type\":\"chat\""), capturedBody.get());
        assertTrue(capturedBody.get().contains("\"endpoint\":\"http://x\""), capturedBody.get());
        assertTrue(!capturedBody.get().contains("\"kind\""), "kind must not leak into POST body: " + capturedBody.get());
        assertTrue(!capturedBody.get().contains("\"spec\""), "spec wrapper must not leak: " + capturedBody.get());
    }

    @Test
    void modelAddRejectsWrongKindEnvelope(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("wrong.yaml");
        Files.writeString(body, """
                kind: Role
                name: roles/platform/foo
                spec:
                  costLimit: { day: 1.0 }
                """);

        Result r = run(config, apiKeyFile(tmp),
                "model", "add", "--name", "models/public/x", "--from-file", body.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("'kind' is 'Role', expected 'Model'"), r.err);
    }

    @Test
    void modelAddEnvelopeNameMismatchWarnsButProceeds(@TempDir Path tmp) throws Exception {
        // --name is authoritative — the envelope's `name` field is informational. Warn loudly
        // but don't block, so the same envelope file can be staged into several names.
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("envelope.yaml");
        Files.writeString(body, """
                kind: Model
                name: models/public/declared-name
                spec:
                  type: chat
                  endpoint: "http://x"
                """);
        respond("/v1/models/public/different-name", 201, "{\"name\":\"different-name\"}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "add", "--name", "models/public/different-name", "--from-file", body.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.err.contains("[warn]"), r.err);
        assertTrue(r.err.contains("declared-name"), r.err);
        assertTrue(r.err.contains("different-name"), r.err);
    }

    @Test
    void modelAddRawSpecBackwardCompat(@TempDir Path tmp) throws Exception {
        // Files without a {kind,spec} envelope continue to be treated as the raw entity body.
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("raw.yaml");
        Files.writeString(body, "type: chat\nendpoint: http://raw\n");
        java.util.concurrent.atomic.AtomicReference<String> capturedBody = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v1/models/public/raw", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 201, "{\"name\":\"raw\"}");
        });

        Result r = run(config, apiKeyFile(tmp),
                "model", "add", "--name", "models/public/raw", "--from-file", body.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(capturedBody.get().contains("\"type\":\"chat\""), capturedBody.get());
        assertTrue(capturedBody.get().contains("\"endpoint\":\"http://raw\""), capturedBody.get());
    }

    @Test
    void modelUpdate200HappyPathSendsMergedBodyAndAutoIfMatch(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        java.util.concurrent.atomic.AtomicReference<String> putBody = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> ifMatch = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v1/models/public/m", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("ETag", "\"v1\"");
                send(exchange, 200, "{\"name\":\"m\",\"endpoint\":\"http://old\",\"pricing\":{\"prompt\":1.0}}");
            } else {
                ifMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
                putBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.getResponseHeaders().add("ETag", "\"v2\"");
                send(exchange, 200, "{\"name\":\"m\"}");
            }
        });

        Result r = run(config, apiKeyFile(tmp),
                "model", "update", "models/public/m",
                "--set", "endpoint=http://new",
                "--set", "pricing.prompt=0.003");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("Updated models/public/m"), r.out);
        assertEquals("\"v1\"", ifMatch.get());
        assertTrue(putBody.get().contains("\"endpoint\":\"http://new\""), putBody.get());
        assertTrue(putBody.get().contains("\"pricing\":{\"prompt\":0.003}"), putBody.get());
        // Cli.3 (2026-05-08): controller-projected fields (`name`, `status`, `source`,
        // `validationWarnings`) are stripped from the merged body before PUT — the server
        // synthesizes `name` from the URL and rejects the others as Unrecognized.
        org.junit.jupiter.api.Assertions.assertFalse(putBody.get().contains("\"name\""), putBody.get());
    }

    @Test
    void modelUpdate404OnGetExitsFour(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        respond("/v1/models/public/missing", 404, "{\"error\":\"not found\"}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "update", "models/public/missing", "--set", "endpoint=http://x");

        assertEquals(4, r.exitCode);
        assertTrue(r.err.contains("Not found"), r.err);
    }

    @Test
    void modelUpdate412OnPutExitsSix(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        server.createContext("/v1/models/public/m", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("ETag", "\"v1\"");
                send(exchange, 200, "{\"name\":\"m\"}");
            } else {
                send(exchange, 412, "{\"error\":\"stale\"}");
            }
        });

        Result r = run(config, apiKeyFile(tmp),
                "model", "update", "models/public/m", "--set", "endpoint=http://x");

        assertEquals(6, r.exitCode);
        assertTrue(r.err.contains("Stale ETag"), r.err);
    }

    @Test
    void modelUpdateExplicitIfMatchOverridesGetEtag(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        java.util.concurrent.atomic.AtomicReference<String> ifMatch = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v1/models/public/m", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("ETag", "\"v1\"");
                send(exchange, 200, "{\"name\":\"m\"}");
            } else {
                ifMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
                send(exchange, 200, "{\"name\":\"m\"}");
            }
        });

        Result r = run(config, apiKeyFile(tmp),
                "model", "update", "models/public/m",
                "--set", "endpoint=http://x",
                "--if-match", "\"explicit\"");

        assertEquals(0, r.exitCode, r.err);
        assertEquals("\"explicit\"", ifMatch.get());
    }

    @Test
    void modelUpdateRejectsSimpleName(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);

        Result r = run(config, apiKeyFile(tmp),
                "model", "update", "gpt-4", "--set", "endpoint=http://x");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("canonical id"), r.err);
    }

    @Test
    void modelUpdateRejectsInvalidSetPair(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        server.createContext("/v1/models/public/m", exchange -> {
            exchange.getResponseHeaders().add("ETag", "\"v1\"");
            send(exchange, 200, "{\"name\":\"m\"}");
        });

        Result r = run(config, apiKeyFile(tmp),
                "model", "update", "models/public/m", "--set", "noequalshere");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("--set"), r.err);
    }

    @Test
    void modelUpdateAcceptsTypedJsonValues(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        java.util.concurrent.atomic.AtomicReference<String> putBody = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v1/models/public/m", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("ETag", "\"v1\"");
                send(exchange, 200, "{\"name\":\"m\"}");
            } else {
                putBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                send(exchange, 200, "{\"name\":\"m\"}");
            }
        });

        Result r = run(config, apiKeyFile(tmp),
                "model", "update", "models/public/m",
                "--set", "maxTotalTokens=200000",
                "--set", "userRoles=[\"basic\",\"admin\"]",
                "--set", "displayName=Anthropic Claude");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(putBody.get().contains("\"maxTotalTokens\":200000"), putBody.get());
        assertTrue(putBody.get().contains("\"userRoles\":[\"basic\",\"admin\"]"), putBody.get());
        assertTrue(putBody.get().contains("\"displayName\":\"Anthropic Claude\""), putBody.get());
    }

    @Test
    void modelUpdateDryRunPrintsMergedBodyAndDoesNotPut(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        java.util.concurrent.atomic.AtomicBoolean putHit = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/v1/models/public/m", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("ETag", "\"v1\"");
                send(exchange, 200, "{\"name\":\"m\",\"endpoint\":\"http://old\"}");
            } else {
                putHit.set(true);
                send(exchange, 500, "{}");
            }
        });

        Result r = run(config, apiKeyFile(tmp), "--dry-run",
                "model", "update", "models/public/m", "--set", "endpoint=http://new");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("\"endpoint\":\"http://new\""), r.out);
        assertTrue(!putHit.get(), "PUT must not fire on --dry-run");
    }

    @Test
    void modelUpdate401OnGetExitsThree(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        respond("/v1/models/public/m", 401, "{\"error\":\"unauthorized\"}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "update", "models/public/m", "--set", "endpoint=http://x");

        assertEquals(3, r.exitCode);
        assertTrue(r.err.contains("401"), r.err);
    }

    @Test
    void modelUpdateRejectsSetOverwritingNonObject(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        server.createContext("/v1/models/public/m", exchange -> {
            exchange.getResponseHeaders().add("ETag", "\"v1\"");
            send(exchange, 200, "{\"name\":\"m\",\"pricing\":1.5}");
        });

        Result r = run(config, apiKeyFile(tmp),
                "model", "update", "models/public/m", "--set", "pricing.prompt=0.003");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("non-object"), r.err);
        assertTrue(r.err.contains("pricing"), r.err);
    }

    @Test
    void modelUpdate403ExitsThree(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        server.createContext("/v1/models/public/m", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("ETag", "\"v1\"");
                send(exchange, 200, "{\"name\":\"m\"}");
            } else {
                send(exchange, 403, "{\"error\":\"forbidden\"}");
            }
        });

        Result r = run(config, apiKeyFile(tmp),
                "model", "update", "models/public/m", "--set", "endpoint=http://x");

        assertEquals(3, r.exitCode);
    }

    @Test
    void modelUpdateNoSetsStillRoundTrips(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        java.util.concurrent.atomic.AtomicReference<String> putBody = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v1/models/public/m", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("ETag", "\"v1\"");
                send(exchange, 200, "{\"name\":\"m\",\"endpoint\":\"http://x\"}");
            } else {
                putBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                send(exchange, 200, "{\"name\":\"m\"}");
            }
        });

        Result r = run(config, apiKeyFile(tmp),
                "model", "update", "models/public/m");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(putBody.get().contains("\"endpoint\":\"http://x\""), putBody.get());
    }

    @Test
    void modelDelete204HappyPath(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        java.util.concurrent.atomic.AtomicReference<String> method = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v1/models/public/m", exchange -> {
            method.set(exchange.getRequestMethod());
            send(exchange, 204, "");
        });

        Result r = run(config, apiKeyFile(tmp), "model", "delete", "models/public/m");

        assertEquals(0, r.exitCode, r.err);
        assertEquals("DELETE", method.get());
        assertTrue(r.out.contains("Deleted models/public/m"), r.out);
    }

    @Test
    void modelDelete404ExitsFour(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        respond("/v1/models/public/missing", 404, "{\"error\":\"not found\"}");

        Result r = run(config, apiKeyFile(tmp), "model", "delete", "models/public/missing");

        assertEquals(4, r.exitCode);
        assertTrue(r.err.contains("Not found"), r.err);
    }

    @Test
    void modelDelete412OnStaleIfMatchExitsSix(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        java.util.concurrent.atomic.AtomicReference<String> ifMatch = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v1/models/public/m", exchange -> {
            ifMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
            send(exchange, 412, "{\"error\":\"stale\"}");
        });

        Result r = run(config, apiKeyFile(tmp),
                "model", "delete", "models/public/m", "--if-match", "\"stale\"");

        assertEquals(6, r.exitCode);
        assertEquals("\"stale\"", ifMatch.get());
        assertTrue(r.err.contains("Stale ETag"), r.err);
    }

    @Test
    void modelDeleteWithoutIfMatchOmitsHeader(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        java.util.concurrent.atomic.AtomicReference<String> ifMatch = new java.util.concurrent.atomic.AtomicReference<>("present");
        server.createContext("/v1/models/public/m", exchange -> {
            ifMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
            send(exchange, 204, "");
        });

        Result r = run(config, apiKeyFile(tmp), "model", "delete", "models/public/m");

        assertEquals(0, r.exitCode, r.err);
        assertEquals(null, ifMatch.get());
    }

    @Test
    void modelDeleteRejectsSimpleName(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);

        Result r = run(config, apiKeyFile(tmp), "model", "delete", "gpt-4");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("canonical id"), r.err);
    }

    @Test
    void modelDeleteDryRunPrintsAndDoesNotHit(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        java.util.concurrent.atomic.AtomicBoolean hit = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/v1/models/public/m", exchange -> {
            hit.set(true);
            send(exchange, 500, "{}");
        });

        Result r = run(config, apiKeyFile(tmp), "--dry-run",
                "model", "delete", "models/public/m");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("Would delete models/public/m"), r.out);
        assertTrue(!hit.get(), "DELETE must not fire on --dry-run");
    }

    @Test
    void modelDelete401ExitsThree(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        respond("/v1/models/public/m", 401, "{\"error\":\"unauthorized\"}");

        Result r = run(config, apiKeyFile(tmp), "model", "delete", "models/public/m");

        assertEquals(3, r.exitCode);
        assertTrue(r.err.contains("401"), r.err);
    }

    @Test
    void modelDelete403ExitsThree(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        respond("/v1/models/public/m", 403, "{\"error\":\"forbidden\"}");

        Result r = run(config, apiKeyFile(tmp), "model", "delete", "models/public/m");

        assertEquals(3, r.exitCode);
        assertTrue(r.err.contains("403"), r.err);
    }

    @Test
    void modelDelete500ExitsOne(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        respond("/v1/models/public/m", 500, "{\"error\":\"internal\"}");

        Result r = run(config, apiKeyFile(tmp), "model", "delete", "models/public/m");

        assertEquals(1, r.exitCode);
        assertTrue(r.err.contains("500"), r.err);
    }

    @Test
    void modelValidate200ValidExitsZero(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.json");
        Files.writeString(body, "{\"type\":\"chat\",\"endpoint\":\"http://x\"}");
        java.util.concurrent.atomic.AtomicReference<String> sentBody = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v1/admin/validate", exchange -> {
            sentBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}");
        });

        Result r = run(config, apiKeyFile(tmp),
                "model", "validate", "--name", "models/public/m", "--from-file", body.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("Valid: models/public/m"), r.out);
        assertTrue(sentBody.get().contains("\"manifests\""), sentBody.get());
        assertTrue(sentBody.get().contains("\"kind\":\"Model\""), sentBody.get());
        assertTrue(sentBody.get().contains("\"name\":\"m\""), sentBody.get());
        assertTrue(sentBody.get().contains("\"spec\":{\"type\":\"chat\",\"endpoint\":\"http://x\"}"), sentBody.get());
    }

    @Test
    void modelValidate422FailedExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.json");
        Files.writeString(body, "{\"type\":\"chat\",\"endpoint\":\"http://x\",\"interceptors\":[\"missing\"]}");
        respond("/v1/admin/validate", 422,
                "{\"valid\":0,\"failed\":1,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"FAILED\",\"error\":\"interceptor 'missing' not found\"}]}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "validate", "--name", "models/public/m", "--from-file", body.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("interceptor 'missing' not found"), r.err);
        assertTrue(r.err.contains("FAILED"), r.err);
    }

    @Test
    void modelValidate422SkippedNotPrintedAsFailure(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.json");
        Files.writeString(body, "{\"type\":\"chat\"}");
        respond("/v1/admin/validate", 422,
                "{\"valid\":0,\"failed\":1,\"results\":["
                        + "{\"entityId\":\"models/public/m\",\"status\":\"FAILED\",\"error\":\"bad endpoint\"},"
                        + "{\"entityId\":\"models/public/sibling\",\"status\":\"skipped\"}"
                        + "]}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "validate", "--name", "models/public/m", "--from-file", body.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("FAILED"), r.err);
        assertTrue(r.err.contains("bad endpoint"), r.err);
        assertTrue(!r.err.contains("sibling"), "skipped entries must not be printed as failures: " + r.err);
    }

    @Test
    void modelValidate200WithFailedExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.json");
        Files.writeString(body, "{\"type\":\"chat\"}");
        respond("/v1/admin/validate", 200,
                "{\"valid\":0,\"failed\":1,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"FAILED\",\"error\":\"missing endpoint\"}]}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "validate", "--name", "models/public/m", "--from-file", body.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("missing endpoint"), r.err);
    }

    @Test
    void modelValidate400ExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.json");
        Files.writeString(body, "{\"type\":\"chat\"}");
        respond("/v1/admin/validate", 400, "{\"error\":\"missing manifests\"}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "validate", "--name", "models/public/m", "--from-file", body.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("400"), r.err);
    }

    @Test
    void modelValidate403ExitsThree(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.json");
        Files.writeString(body, "{\"type\":\"chat\"}");
        respond("/v1/admin/validate", 403, "{\"error\":\"forbidden\"}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "validate", "--name", "models/public/m", "--from-file", body.toString());

        assertEquals(3, r.exitCode);
        assertTrue(r.err.contains("403"), r.err);
    }

    @Test
    void modelValidate401ExitsThree(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.json");
        Files.writeString(body, "{\"type\":\"chat\"}");
        respond("/v1/admin/validate", 401, "{}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "validate", "--name", "models/public/m", "--from-file", body.toString());

        assertEquals(3, r.exitCode);
    }

    @Test
    void modelValidate500ExitsOne(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.json");
        Files.writeString(body, "{\"type\":\"chat\"}");
        respond("/v1/admin/validate", 500, "{\"error\":\"internal\"}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "validate", "--name", "models/public/m", "--from-file", body.toString());

        assertEquals(1, r.exitCode);
    }

    @Test
    void modelValidateRejectsSimpleName(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.json");
        Files.writeString(body, "{}");

        Result r = run(config, apiKeyFile(tmp),
                "model", "validate", "--name", "gpt-4", "--from-file", body.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("canonical id"), r.err);
    }

    @Test
    void modelValidateDryRunPrintsEnvelopeAndDoesNotPost(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.json");
        Files.writeString(body, "{\"type\":\"chat\",\"endpoint\":\"http://x\"}");
        java.util.concurrent.atomic.AtomicBoolean hit = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/v1/admin/validate", exchange -> {
            hit.set(true);
            send(exchange, 500, "{}");
        });

        Result r = run(config, apiKeyFile(tmp), "--dry-run",
                "model", "validate", "--name", "models/public/m", "--from-file", body.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("\"manifests\""), r.out);
        assertTrue(r.out.contains("\"kind\":\"Model\""), r.out);
        assertTrue(r.out.contains("\"precheck\":true"), r.out);
        assertTrue(!hit.get(), "Server must not be called on --dry-run");
    }

    @Test
    void modelValidateYamlFromFileSendsJson(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("m.yaml");
        Files.writeString(body, "type: chat\nendpoint: http://yaml-host\n");
        java.util.concurrent.atomic.AtomicReference<String> sentBody = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v1/admin/validate", exchange -> {
            sentBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}");
        });

        Result r = run(config, apiKeyFile(tmp),
                "model", "validate", "--name", "models/public/m", "--from-file", body.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(sentBody.get().contains("\"endpoint\":\"http://yaml-host\""), sentBody.get());
        assertTrue(!sentBody.get().contains("type: chat"), "Spec must be JSON, not YAML");
    }

    @Test
    void modelValidateInvalidJsonFromFileExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("bad.json");
        Files.writeString(body, "{not json");

        Result r = run(config, apiKeyFile(tmp),
                "model", "validate", "--name", "models/public/m", "--from-file", body.toString());

        assertEquals(2, r.exitCode);
    }

    @Test
    void modelValidateAcceptsManifestEnvelope(@TempDir Path tmp) throws Exception {
        // The shape used in sample/dial-cli/manifests/*.yaml — `{kind, name, spec}` envelope —
        // must validate without server-side "Unrecognized field 'kind'" errors. The CLI unwraps
        // the spec before submission to /v1/admin/validate.
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("envelope.yaml");
        Files.writeString(body, """
                kind: Model
                name: models/public/m
                spec:
                  type: chat
                  endpoint: "http://x"
                """);
        java.util.concurrent.atomic.AtomicReference<String> sentBody = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v1/admin/validate", exchange -> {
            sentBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}");
        });

        Result r = run(config, apiKeyFile(tmp),
                "model", "validate", "--name", "models/public/m", "--from-file", body.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(sentBody.get().contains("\"spec\":{\"type\":\"chat\",\"endpoint\":\"http://x\"}"), sentBody.get());
        // 'kind' on the manifest envelope on the wire is fine — that's the apply/validate
        // payload's own kind. It must not appear *inside* the spec, though.
        assertTrue(!sentBody.get().contains("\"spec\":{\"kind\""),
                "kind must not leak into spec body: " + sentBody.get());
    }

    @Test
    void modelValidateRejectsWrongKindEnvelope(@TempDir Path tmp) throws Exception {
        Path config = writeProfileAndKey(tmp);
        Path body = tmp.resolve("wrong.yaml");
        Files.writeString(body, """
                kind: Role
                name: roles/platform/foo
                spec:
                  costLimit: { day: 1.0 }
                """);

        Result r = run(config, apiKeyFile(tmp),
                "model", "validate", "--name", "models/public/m", "--from-file", body.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("'kind' is 'Role', expected 'Model'"), r.err);
    }

    private Path writeTwoEnvProfile(Path tmp, String sourceUrl, String targetUrl) throws Exception {
        Path config = tmp.resolve("config.yaml");
        Files.writeString(config, """
                defaults: { env: dev }
                environments:
                  dev:
                    api_url: "%s"
                    auth: { type: api_key, key_env_var: NONEXISTENT_DIAL_TEST_KEY }
                  uat:
                    api_url: "%s"
                    auth: { type: api_key, key_env_var: NONEXISTENT_DIAL_TEST_KEY }
                """.formatted(sourceUrl, targetUrl));
        return config;
    }

    @Test
    void modelPromote200AppliedInvalidWarnsButExitsZero(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            respond("/v1/models/public/m", 200, "{\"name\":\"m\",\"type\":\"chat\"}");
            target.createContext("/v1/admin/apply", exchange -> send(exchange, 200,
                    "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied_invalid\",\"error\":\"dangling ref\"}]}"));

            Result r = run(config, apiKeyFile(tmp),
                    "model", "promote", "--from", "dev", "--to", "uat",
                    "--name", "models/public/m");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(r.out.contains("Promoted models/public/m"), r.out);
            assertTrue(r.err.contains("warn"), r.err);
            assertTrue(r.err.contains("applied with validation warnings"), r.err);
            assertTrue(r.err.contains("dangling ref"), r.err);
        } finally {
            target.stop(0);
        }
    }


    @Test
    void modelPromote200HappyPath(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            // Source GET
            respond("/v1/models/public/m", 200,
                    "{\"name\":\"m\",\"type\":\"chat\",\"endpoint\":\"http://src/x\"}");
            // Target apply
            java.util.concurrent.atomic.AtomicReference<String> applyBody = new java.util.concurrent.atomic.AtomicReference<>();
            target.createContext("/v1/admin/apply", exchange -> {
                applyBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                send(exchange, 200,
                        "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied\"}]}");
            });

            Result r = run(config, apiKeyFile(tmp),
                    "model", "promote", "--from", "dev", "--to", "uat",
                    "--name", "models/public/m");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(r.out.contains("Promoted models/public/m from dev to uat"), r.out);
            assertTrue(applyBody.get().contains("\"manifests\""), applyBody.get());
            assertTrue(applyBody.get().contains("\"kind\":\"Model\""), applyBody.get());
            assertTrue(applyBody.get().contains("\"name\":\"m\""), applyBody.get());
            assertTrue(applyBody.get().contains("\"precheck\":true"), applyBody.get());
            assertTrue(applyBody.get().contains("\"endpoint\":\"http://src/x\""), applyBody.get());
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelPromoteSource404ExitsFour(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            respond("/v1/models/public/missing", 404, "{\"error\":\"not found\"}");
            java.util.concurrent.atomic.AtomicBoolean targetHit = new java.util.concurrent.atomic.AtomicBoolean();
            target.createContext("/v1/admin/apply", exchange -> {
                targetHit.set(true);
                send(exchange, 200, "{}");
            });

            Result r = run(config, apiKeyFile(tmp),
                    "model", "promote", "--from", "dev", "--to", "uat",
                    "--name", "models/public/missing");

            assertEquals(4, r.exitCode);
            assertTrue(r.err.contains("Source dev"), r.err);
            assertTrue(r.err.contains("Not found"), r.err);
            assertTrue(!targetHit.get(), "Target apply must not fire when source GET fails");
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelPromoteTarget422ExitsTwo(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            respond("/v1/models/public/m", 200,
                    "{\"name\":\"m\",\"type\":\"chat\",\"endpoint\":\"http://src\"}");
            target.createContext("/v1/admin/apply", exchange -> send(exchange, 422,
                    "{\"applied\":0,\"failed\":1,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"FAILED\",\"error\":\"missing interceptor\"}]}"));

            Result r = run(config, apiKeyFile(tmp),
                    "model", "promote", "--from", "dev", "--to", "uat",
                    "--name", "models/public/m");

            assertEquals(2, r.exitCode);
            assertTrue(r.err.contains("missing interceptor"), r.err);
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelPromoteTarget403ExitsThree(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            respond("/v1/models/public/m", 200, "{\"name\":\"m\",\"type\":\"chat\"}");
            target.createContext("/v1/admin/apply", exchange -> send(exchange, 403, "{\"error\":\"forbidden\"}"));

            Result r = run(config, apiKeyFile(tmp),
                    "model", "promote", "--from", "dev", "--to", "uat",
                    "--name", "models/public/m");

            assertEquals(3, r.exitCode);
            assertTrue(r.err.contains("Target uat"), r.err);
            assertTrue(r.err.contains("403"), r.err);
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelPromoteDryRunPrintsEnvelopeAndDoesNotApply(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            respond("/v1/models/public/m", 200,
                    "{\"name\":\"m\",\"type\":\"chat\",\"endpoint\":\"http://src\"}");
            java.util.concurrent.atomic.AtomicBoolean targetHit = new java.util.concurrent.atomic.AtomicBoolean();
            target.createContext("/v1/admin/apply", exchange -> {
                targetHit.set(true);
                send(exchange, 500, "{}");
            });

            Result r = run(config, apiKeyFile(tmp), "--dry-run",
                    "model", "promote", "--from", "dev", "--to", "uat",
                    "--name", "models/public/m");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(r.out.contains("\"manifests\""), r.out);
            assertTrue(r.out.contains("\"kind\":\"Model\""), r.out);
            assertTrue(r.out.contains("\"precheck\":true"), r.out);
            assertTrue(!targetHit.get(), "Target apply must not fire on --dry-run");
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelPromoteUnknownSourceEnvExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = writeTwoEnvProfile(tmp, baseUrl, baseUrl);

        Result r = run(config, apiKeyFile(tmp),
                "model", "promote", "--from", "ghost", "--to", "uat",
                "--name", "models/public/m");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("'ghost' not found"), r.err);
    }

    @Test
    void modelPromoteUnknownTargetEnvExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = writeTwoEnvProfile(tmp, baseUrl, baseUrl);

        Result r = run(config, apiKeyFile(tmp),
                "model", "promote", "--from", "dev", "--to", "phantom",
                "--name", "models/public/m");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("'phantom' not found"), r.err);
    }

    @Test
    void modelPromoteRejectsSimpleName(@TempDir Path tmp) throws Exception {
        Path config = writeTwoEnvProfile(tmp, baseUrl, baseUrl);

        Result r = run(config, apiKeyFile(tmp),
                "model", "promote", "--from", "dev", "--to", "uat", "--name", "gpt-4");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("canonical id"), r.err);
    }

    @Test
    void modelDiffSingleModelChangedField(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            respond("/v1/models/public/m", 200, "{\"name\":\"m\",\"endpoint\":\"http://src\"}");
            target.createContext("/v1/models/public/m", exchange ->
                    send(exchange, 200, "{\"name\":\"m\",\"endpoint\":\"http://tgt\"}"));

            Result r = run(config, apiKeyFile(tmp),
                    "model", "diff", "--source", "dev", "--target", "uat",
                    "--name", "models/public/m");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(r.out.contains("~ endpoint"), r.out);
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelDiffSingleModelNoDifferences(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            respond("/v1/models/public/m", 200, "{\"name\":\"m\",\"endpoint\":\"http://x\"}");
            target.createContext("/v1/models/public/m", exchange ->
                    send(exchange, 200, "{\"name\":\"m\",\"endpoint\":\"http://x\"}"));

            Result r = run(config, apiKeyFile(tmp),
                    "model", "diff", "--source", "dev", "--target", "uat",
                    "--name", "models/public/m");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(r.out.contains("No differences."), r.out);
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelDiffSingleModel404OnSourceShowsAdded(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            respond("/v1/models/public/m", 404, "{\"error\":\"not found\"}");
            target.createContext("/v1/models/public/m", exchange ->
                    send(exchange, 200, "{\"name\":\"m\",\"endpoint\":\"http://x\"}"));

            Result r = run(config, apiKeyFile(tmp),
                    "model", "diff", "--source", "dev", "--target", "uat",
                    "--name", "models/public/m");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(r.out.contains("+ name"), r.out);
            assertTrue(r.out.contains("+ endpoint"), r.out);
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelDiffSingleModel404OnTargetShowsRemoved(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            respond("/v1/models/public/m", 200, "{\"name\":\"m\",\"endpoint\":\"http://x\"}");
            target.createContext("/v1/models/public/m", exchange ->
                    send(exchange, 404, "{\"error\":\"not found\"}"));

            Result r = run(config, apiKeyFile(tmp),
                    "model", "diff", "--source", "dev", "--target", "uat",
                    "--name", "models/public/m");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(r.out.contains("- name"), r.out);
            assertTrue(r.out.contains("- endpoint"), r.out);
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelDiffListAddedRemovedAndChanged(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            server.createContext("/v1/models/public/", exchange -> send(exchange, 200, """
                    {"items":[
                      {"name":"shared","endpoint":"http://src"},
                      {"name":"src-only","endpoint":"http://src-only"}
                    ],"hasMore":false}"""));
            target.createContext("/v1/models/public/", exchange -> send(exchange, 200, """
                    {"items":[
                      {"name":"shared","endpoint":"http://tgt"},
                      {"name":"tgt-only","endpoint":"http://tgt-only"}
                    ],"hasMore":false}"""));

            Result r = run(config, apiKeyFile(tmp),
                    "model", "diff", "--source", "dev", "--target", "uat");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(r.out.contains("- src-only"), r.out);
            assertTrue(r.out.contains("+ tgt-only"), r.out);
            assertTrue(r.out.contains("~ shared.endpoint"), r.out);
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelDiffListEmptySourceShowsAllAdded(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            server.createContext("/v1/models/public/", exchange -> send(exchange, 200,
                    "{\"items\":[],\"hasMore\":false}"));
            target.createContext("/v1/models/public/", exchange -> send(exchange, 200,
                    "{\"items\":[{\"name\":\"m\",\"endpoint\":\"http://x\"}],\"hasMore\":false}"));

            Result r = run(config, apiKeyFile(tmp),
                    "model", "diff", "--source", "dev", "--target", "uat");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(r.out.contains("+ m"), r.out);
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelDiffListIdenticalReportsNoDifferences(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            String identical = "{\"items\":[{\"name\":\"m\",\"endpoint\":\"http://x\"}],\"hasMore\":false}";
            server.createContext("/v1/models/public/", exchange -> send(exchange, 200, identical));
            target.createContext("/v1/models/public/", exchange -> send(exchange, 200, identical));

            Result r = run(config, apiKeyFile(tmp),
                    "model", "diff", "--source", "dev", "--target", "uat");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(r.out.contains("No differences."), r.out);
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelDiffListWarnsOnHasMore(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            server.createContext("/v1/models/public/", exchange -> send(exchange, 200,
                    "{\"items\":[{\"name\":\"m\"}],\"hasMore\":true}"));
            target.createContext("/v1/models/public/", exchange -> send(exchange, 200,
                    "{\"items\":[{\"name\":\"m\"}],\"hasMore\":false}"));

            Result r = run(config, apiKeyFile(tmp),
                    "model", "diff", "--source", "dev", "--target", "uat");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(r.err.contains("[warn]"), r.err);
            assertTrue(r.err.contains("dev"), r.err);
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelDiffSourceAuthFailureExitsOne(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfile(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort());
            respond("/v1/models/public/m", 401, "{\"error\":\"unauthorized\"}");

            Result r = run(config, apiKeyFile(tmp),
                    "model", "diff", "--source", "dev", "--target", "uat",
                    "--name", "models/public/m");

            assertEquals(1, r.exitCode);
            assertTrue(r.err.contains("dev"), r.err);
            assertTrue(r.err.contains("401"), r.err);
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelDiffRejectsSimpleName(@TempDir Path tmp) throws Exception {
        Path config = writeTwoEnvProfile(tmp, baseUrl, baseUrl);

        Result r = run(config, apiKeyFile(tmp),
                "model", "diff", "--source", "dev", "--target", "uat", "--name", "gpt-4");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("canonical id"), r.err);
    }

    @Test
    void modelDiffRejectsAmbiguousPartialCanonicalId(@TempDir Path tmp) throws Exception {
        Path config = writeTwoEnvProfile(tmp, baseUrl, baseUrl);

        Result r = run(config, apiKeyFile(tmp),
                "model", "diff", "--source", "dev", "--target", "uat",
                "--name", "models/public/foo/bar");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("canonical id"), r.err);
    }

    private Path writeTwoEnvProfileWithTemplates(Path tmp, String sourceUrl, String targetUrl,
                                                 String extraYaml) throws Exception {
        Path config = tmp.resolve("config.yaml");
        Files.writeString(config, ("""
                defaults: { env: dev }
                environments:
                  dev:
                    api_url: "%s"
                    auth: { type: api_key, key_env_var: NONEXISTENT_DIAL_TEST_KEY }
                    vars:
                      adapter_host: "http://dev-host:8080"
                      region: "us-east-1"
                  uat:
                    api_url: "%s"
                    auth: { type: api_key, key_env_var: NONEXISTENT_DIAL_TEST_KEY }
                    vars:
                      adapter_host: "http://uat-host:8080"
                      region: "eu-west-1"
                """ + extraYaml).formatted(sourceUrl, targetUrl));
        return config;
    }

    @Test
    void modelPromoteWithTemplateNameUsesTargetEnvVars(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            String templates = """
                    templates:
                      bedrock-chat:
                        fields:
                          endpoint: "${vars.adapter_host}/openai/deployments/${entity.name}/chat/completions"
                    """;
            Path config = writeTwoEnvProfileWithTemplates(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort(), templates);
            respond("/v1/models/public/m", 200,
                    "{\"name\":\"m\",\"type\":\"chat\",\"endpoint\":\"http://dev-host:8080/openai/deployments/m/chat/completions\"}");
            java.util.concurrent.atomic.AtomicReference<String> applyBody = new java.util.concurrent.atomic.AtomicReference<>();
            target.createContext("/v1/admin/apply", exchange -> {
                applyBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                send(exchange, 200,
                        "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied\"}]}");
            });

            Result r = run(config, apiKeyFile(tmp),
                    "model", "promote", "--from", "dev", "--to", "uat",
                    "--name", "models/public/m",
                    "--template", "bedrock-chat");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(applyBody.get().contains("http://uat-host:8080/openai/deployments/m/chat/completions"),
                    "Expected target env vars resolved; got: " + applyBody.get());
            assertTrue(!applyBody.get().contains("dev-host"),
                    "Source-env hostname must be replaced by target's; got: " + applyBody.get());
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelPromoteWithTemplateAutoSinglematchPicksIt(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            String templates = """
                    templates:
                      bedrock-chat:
                        fields:
                          endpoint: "${vars.adapter_host}/openai/deployments/${entity.name}/chat/completions"
                      other-template:
                        fields:
                          endpoint: "${vars.adapter_host}/different/path"
                    """;
            Path config = writeTwoEnvProfileWithTemplates(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort(), templates);
            respond("/v1/models/public/m", 200,
                    "{\"name\":\"m\",\"type\":\"chat\","
                            + "\"endpoint\":\"http://dev-host:8080/openai/deployments/m/chat/completions\","
                            + "\"displayName\":\"My Model\"}");
            java.util.concurrent.atomic.AtomicReference<String> applyBody = new java.util.concurrent.atomic.AtomicReference<>();
            target.createContext("/v1/admin/apply", exchange -> {
                applyBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                send(exchange, 200,
                        "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied\"}]}");
            });

            Result r = run(config, apiKeyFile(tmp),
                    "model", "promote", "--from", "dev", "--to", "uat",
                    "--name", "models/public/m",
                    "--template", "auto");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(applyBody.get().contains("http://uat-host:8080/openai/deployments/m/chat/completions"),
                    "Auto must pick bedrock-chat and re-resolve against uat; got: " + applyBody.get());
            assertTrue(applyBody.get().contains("\"displayName\":\"My Model\""),
                    "Non-template fields must be preserved; got: " + applyBody.get());
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelPromoteWithTemplateAutoNoMatchExitsTwo(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            String templates = """
                    templates:
                      bedrock-chat:
                        fields:
                          endpoint: "${vars.adapter_host}/openai/deployments/${entity.name}/chat/completions"
                    """;
            Path config = writeTwoEnvProfileWithTemplates(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort(), templates);
            respond("/v1/models/public/m", 200,
                    "{\"name\":\"m\",\"type\":\"chat\",\"endpoint\":\"http://manual-edit/x\"}");
            java.util.concurrent.atomic.AtomicBoolean targetHit = new java.util.concurrent.atomic.AtomicBoolean();
            target.createContext("/v1/admin/apply", exchange -> {
                targetHit.set(true);
                send(exchange, 200, "{}");
            });

            Result r = run(config, apiKeyFile(tmp),
                    "model", "promote", "--from", "dev", "--to", "uat",
                    "--name", "models/public/m",
                    "--template", "auto");

            assertEquals(2, r.exitCode);
            assertTrue(r.err.contains("No template matches"), r.err);
            assertTrue(r.err.contains("bedrock-chat"), r.err);
            assertTrue(!targetHit.get(), "Apply must not fire on auto-no-match");
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelPromoteWithTemplateAutoMultiMatchExitsTwo(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            String templates = """
                    templates:
                      template-a:
                        fields:
                          endpoint: "http://dev-host:8080/x"
                      template-b:
                        fields:
                          endpoint: "http://dev-host:8080/x"
                    """;
            Path config = writeTwoEnvProfileWithTemplates(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort(), templates);
            respond("/v1/models/public/m", 200,
                    "{\"name\":\"m\",\"endpoint\":\"http://dev-host:8080/x\"}");
            java.util.concurrent.atomic.AtomicBoolean targetHit = new java.util.concurrent.atomic.AtomicBoolean();
            target.createContext("/v1/admin/apply", exchange -> {
                targetHit.set(true);
                send(exchange, 200, "{}");
            });

            Result r = run(config, apiKeyFile(tmp),
                    "model", "promote", "--from", "dev", "--to", "uat",
                    "--name", "models/public/m",
                    "--template", "auto");

            assertEquals(2, r.exitCode);
            assertTrue(r.err.contains("Multiple templates match"), r.err);
            assertTrue(r.err.contains("template-a"), r.err);
            assertTrue(r.err.contains("template-b"), r.err);
            assertTrue(!targetHit.get(), "Apply must not fire on auto-multi-match");
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelPromoteAsIsWarnsOnSourceHostname(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfileWithTemplates(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort(), "");
            respond("/v1/models/public/m", 200,
                    "{\"name\":\"m\",\"endpoint\":\"http://dev-host:8080/openai/x\"}");
            target.createContext("/v1/admin/apply", exchange -> send(exchange, 200,
                    "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied\"}]}"));

            Result r = run(config, apiKeyFile(tmp),
                    "model", "promote", "--from", "dev", "--to", "uat",
                    "--name", "models/public/m");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(r.err.contains("WARN"), r.err);
            assertTrue(r.err.contains("hostname"), r.err);
            assertTrue(r.err.contains("dev-host") || r.err.contains("adapter_host"), r.err);
            assertTrue(r.err.contains("--template"), r.err);
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelPromoteWithTemplateNameSuppressesHostnameWarning(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            String templates = """
                    templates:
                      bedrock-chat:
                        fields:
                          endpoint: "${vars.adapter_host}/openai/x"
                    """;
            Path config = writeTwoEnvProfileWithTemplates(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort(), templates);
            respond("/v1/models/public/m", 200,
                    "{\"name\":\"m\",\"endpoint\":\"http://dev-host:8080/openai/x\"}");
            target.createContext("/v1/admin/apply", exchange -> send(exchange, 200,
                    "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied\"}]}"));

            Result r = run(config, apiKeyFile(tmp),
                    "model", "promote", "--from", "dev", "--to", "uat",
                    "--name", "models/public/m",
                    "--template", "bedrock-chat");

            assertEquals(0, r.exitCode, r.err);
            assertTrue(!r.err.contains("WARN"), "Template-resolved endpoint should suppress warning; err=" + r.err);
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelPromoteAutoWithNoTemplatesInProfileExitsTwo(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfileWithTemplates(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort(), "");
            respond("/v1/models/public/m", 200, "{\"name\":\"m\"}");
            java.util.concurrent.atomic.AtomicBoolean targetHit = new java.util.concurrent.atomic.AtomicBoolean();
            target.createContext("/v1/admin/apply", exchange -> {
                targetHit.set(true);
                send(exchange, 200, "{}");
            });

            Result r = run(config, apiKeyFile(tmp),
                    "model", "promote", "--from", "dev", "--to", "uat",
                    "--name", "models/public/m",
                    "--template", "auto");

            assertEquals(2, r.exitCode);
            assertTrue(r.err.contains("No templates defined in profile"), r.err);
            assertTrue(!targetHit.get(), "Apply must not fire when profile has no templates");
        } finally {
            target.stop(0);
        }
    }

    @Test
    void modelPromoteUnknownTemplateExitsTwo(@TempDir Path tmp) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.start();
        try {
            Path config = writeTwoEnvProfileWithTemplates(tmp, baseUrl,
                    "http://localhost:" + target.getAddress().getPort(), "");
            respond("/v1/models/public/m", 200, "{\"name\":\"m\"}");
            java.util.concurrent.atomic.AtomicBoolean targetHit = new java.util.concurrent.atomic.AtomicBoolean();
            target.createContext("/v1/admin/apply", exchange -> {
                targetHit.set(true);
                send(exchange, 200, "{}");
            });

            Result r = run(config, apiKeyFile(tmp),
                    "model", "promote", "--from", "dev", "--to", "uat",
                    "--name", "models/public/m",
                    "--template", "ghost");

            assertEquals(2, r.exitCode);
            assertTrue(r.err.contains("ghost"), r.err);
            assertTrue(!targetHit.get(), "Apply must not fire on unknown template");
        } finally {
            target.stop(0);
        }
    }

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
}

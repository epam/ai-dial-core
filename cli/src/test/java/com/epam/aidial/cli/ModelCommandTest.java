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
        assertTrue(r.err.contains("404"), r.err);
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

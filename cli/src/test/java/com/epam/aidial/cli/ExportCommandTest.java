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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportCommandTest {

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

    private Path setup(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("key.txt"), "test-key");
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

    private Result run(Path config, Path tmp, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new DialCli());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        String[] full = new String[4 + args.length];
        full[0] = "--config";
        full[1] = config.toString();
        full[2] = "--api-key-file";
        full[3] = tmp.resolve("key.txt").toString();
        System.arraycopy(args, 0, full, 4, args.length);
        return new Result(cli.execute(full), out.toString(), err.toString());
    }

    private record Result(int exitCode, String out, String err) { }

    @Test
    void exportToStdoutDefaultJson(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        AtomicReference<String> capturedAccept = new AtomicReference<>();
        server.createContext("/v1/admin/export", exchange -> {
            capturedAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            send(exchange, 200, "{\"models\":[]}");
        });

        Result r = run(config, tmp, "export");

        assertEquals(0, r.exitCode, r.err);
        assertEquals("{\"models\":[]}", r.out);
        assertEquals("application/json", capturedAccept.get());
    }

    @Test
    void exportYamlNegotiatesAcceptHeader(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        AtomicReference<String> capturedAccept = new AtomicReference<>();
        server.createContext("/v1/admin/export", exchange -> {
            capturedAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            send(exchange, 200, "models: []\n");
        });

        Result r = run(config, tmp, "-o", "yaml", "export");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("models: []"), r.out);
        assertEquals("application/yaml", capturedAccept.get());
    }

    @Test
    void exportToFile(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond("/v1/admin/export", 200, "{\"snapshot\":1}");
        Path target = tmp.resolve("export.json");

        Result r = run(config, tmp, "export", "-f", target.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(Files.exists(target));
        assertEquals("{\"snapshot\":1}", Files.readString(target));
        assertEquals("", r.out);
    }

    @Test
    void exportCreatesParentDirectory(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond("/v1/admin/export", 200, "{}");
        Path target = tmp.resolve("a/b/c/export.json");

        Result r = run(config, tmp, "export", "-f", target.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(Files.exists(target));
    }

    @Test
    void exportTableFallsBackToJson(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        AtomicReference<String> capturedAccept = new AtomicReference<>();
        server.createContext("/v1/admin/export", exchange -> {
            capturedAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            send(exchange, 200, "{}");
        });

        Result r = run(config, tmp, "-o", "table", "export");

        assertEquals(0, r.exitCode, r.err);
        assertEquals("application/json", capturedAccept.get());
    }

    @Test
    void exportNoEnvSelectedExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config.yaml");
        Files.writeString(config, "environments: { dev: { api_url: \"http://x\" } }\n");
        Files.writeString(tmp.resolve("key.txt"), "k");

        Result r = run(config, tmp, "export");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("No environment selected"), r.err);
    }

    @Test
    void exportRejectsDirectoryAsOutputFile(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond("/v1/admin/export", 200, "{}");
        Path dir = tmp.resolve("a-directory");
        Files.createDirectories(dir);

        Result r = run(config, tmp, "export", "-f", dir.toString());

        assertEquals(1, r.exitCode);
        assertTrue(r.err.contains("is a directory"), r.err);
    }

    @Test
    void exportPropagatesHttpErrors(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond("/v1/admin/export", 401, "{\"error\":\"unauthorized\"}");

        Result r = run(config, tmp, "export");

        assertEquals(3, r.exitCode);
        assertTrue(r.err.contains("401"), r.err);
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> send(exchange, status, body));
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

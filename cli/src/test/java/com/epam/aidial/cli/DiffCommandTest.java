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

class DiffCommandTest {

    private HttpServer sourceServer;
    private HttpServer targetServer;
    private String sourceUrl;
    private String targetUrl;

    @BeforeEach
    void startServers() throws IOException {
        sourceServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        sourceServer.start();
        sourceUrl = "http://localhost:" + sourceServer.getAddress().getPort();
        targetServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        targetServer.start();
        targetUrl = "http://localhost:" + targetServer.getAddress().getPort();
    }

    @AfterEach
    void stopServers() {
        sourceServer.stop(0);
        targetServer.stop(0);
    }

    private Path setup(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("key.txt"), "test-key");
        Path config = tmp.resolve("config.yaml");
        Files.writeString(config, """
                environments:
                  dev:
                    api_url: "%s"
                    auth: { type: api_key, key_env_var: NONEXISTENT_DIAL_TEST_KEY }
                  prod:
                    api_url: "%s"
                    auth: { type: api_key, key_env_var: NONEXISTENT_DIAL_TEST_KEY }
                """.formatted(sourceUrl, targetUrl));
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

    private static void respond(HttpServer server, String path, int status, String body) {
        server.createContext(path, exchange -> send(exchange, status, body));
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void identicalEnvsReportNoDifferences(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond(sourceServer, "/v1/admin/export", 200, "{\"models\":{\"gpt-4\":{\"endpoint\":\"x\"}}}");
        respond(targetServer, "/v1/admin/export", 200, "{\"models\":{\"gpt-4\":{\"endpoint\":\"x\"}}}");

        Result r = run(config, tmp, "diff", "--source", "dev", "--target", "prod");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("No differences"), r.out);
    }

    @Test
    void detectsAddedAndRemovedAndChangedEntities(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond(sourceServer, "/v1/admin/export", 200,
                "{\"models\":{\"gpt-4\":{\"endpoint\":\"a\"},\"old\":{}}}");
        respond(targetServer, "/v1/admin/export", 200,
                "{\"models\":{\"gpt-4\":{\"endpoint\":\"b\"},\"new\":{}}}");

        Result r = run(config, tmp, "diff", "--source", "dev", "--target", "prod");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("~ models.gpt-4.endpoint"), r.out);
        assertTrue(r.out.contains("+ models.new"), r.out);
        assertTrue(r.out.contains("- models.old"), r.out);
    }

    @Test
    void hitsBothEnvironmentsExactlyOnce(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        java.util.concurrent.atomic.AtomicInteger sourceHits = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger targetHits = new java.util.concurrent.atomic.AtomicInteger();
        sourceServer.createContext("/v1/admin/export", exchange -> {
            sourceHits.incrementAndGet();
            send(exchange, 200, "{}");
        });
        targetServer.createContext("/v1/admin/export", exchange -> {
            targetHits.incrementAndGet();
            send(exchange, 200, "{}");
        });

        Result r = run(config, tmp, "diff", "--source", "dev", "--target", "prod");

        assertEquals(0, r.exitCode, r.err);
        assertEquals(1, sourceHits.get());
        assertEquals(1, targetHits.get());
    }

    @Test
    void usesEachEnvsApiUrl(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        AtomicReference<String> sourceHost = new AtomicReference<>();
        AtomicReference<String> targetHost = new AtomicReference<>();
        sourceServer.createContext("/v1/admin/export", exchange -> {
            sourceHost.set(exchange.getRequestHeaders().getFirst("Host"));
            send(exchange, 200, "{\"a\":1}");
        });
        targetServer.createContext("/v1/admin/export", exchange -> {
            targetHost.set(exchange.getRequestHeaders().getFirst("Host"));
            send(exchange, 200, "{\"a\":2}");
        });

        Result r = run(config, tmp, "diff", "--source", "dev", "--target", "prod");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(sourceHost.get().endsWith(":" + sourceServer.getAddress().getPort()), sourceHost.get());
        assertTrue(targetHost.get().endsWith(":" + targetServer.getAddress().getPort()), targetHost.get());
    }

    @Test
    void unknownEnvExitsTwo(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);

        Result r = run(config, tmp, "diff", "--source", "ghost", "--target", "prod");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("'ghost' not found"), r.err);
    }

    @Test
    void sourceHttpErrorPropagated(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond(sourceServer, "/v1/admin/export", 401, "{\"error\":\"unauthorized\"}");
        respond(targetServer, "/v1/admin/export", 200, "{}");

        Result r = run(config, tmp, "diff", "--source", "dev", "--target", "prod");

        assertEquals(3, r.exitCode);
        assertTrue(r.err.contains("401"), r.err);
    }

    @Test
    void apiUrlOverrideDoesNotApplyToDiff(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        java.util.concurrent.atomic.AtomicInteger sourceHits = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger targetHits = new java.util.concurrent.atomic.AtomicInteger();
        sourceServer.createContext("/v1/admin/export", exchange -> {
            sourceHits.incrementAndGet();
            send(exchange, 200, "{}");
        });
        targetServer.createContext("/v1/admin/export", exchange -> {
            targetHits.incrementAndGet();
            send(exchange, 200, "{}");
        });

        Result r = run(config, tmp, "--api-url", "http://localhost:1", "diff", "--source", "dev", "--target", "prod");

        assertEquals(0, r.exitCode, r.err);
        assertEquals(1, sourceHits.get(), "diff must use env-specific api_url, not the global override");
        assertEquals(1, targetHits.get(), "diff must use env-specific api_url, not the global override");
    }

    @Test
    void usesApiKeyFromKeyFile(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        AtomicReference<String> sourceAuth = new AtomicReference<>();
        AtomicReference<String> targetAuth = new AtomicReference<>();
        sourceServer.createContext("/v1/admin/export", exchange -> {
            sourceAuth.set(exchange.getRequestHeaders().getFirst("Api-Key"));
            send(exchange, 200, "{}");
        });
        targetServer.createContext("/v1/admin/export", exchange -> {
            targetAuth.set(exchange.getRequestHeaders().getFirst("Api-Key"));
            send(exchange, 200, "{}");
        });

        Result r = run(config, tmp, "diff", "--source", "dev", "--target", "prod");

        assertEquals(0, r.exitCode, r.err);
        assertEquals("test-key", sourceAuth.get());
        assertEquals("test-key", targetAuth.get());
    }

    @Test
    void requiresBothFlags(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);

        Result r = run(config, tmp, "diff", "--source", "dev");

        assertEquals(2, r.exitCode);
    }
}

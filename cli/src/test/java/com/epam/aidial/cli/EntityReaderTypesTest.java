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

class EntityReaderTypesTest {

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
    void applicationListUsesPublicBucket(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond("/v1/applications/public/", 200,
                "{\"items\":[{\"name\":\"my-app\"}],\"hasMore\":false}");

        Result r = run(config, tmp, "application", "list");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("NAME"), r.out);
        assertTrue(r.out.contains("my-app"), r.out);
    }

    @Test
    void interceptorListUsesPlatformBucket(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond("/v1/interceptors/platform/", 200,
                "{\"items\":[{\"name\":\"guardrail\"}],\"hasMore\":false}");

        Result r = run(config, tmp, "interceptor", "list");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("guardrail"), r.out);
    }

    @Test
    void roleGetUsesPlatformBucketForSimpleName(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond("/v1/roles/platform/viewer", 200, "{\"name\":\"viewer\"}");

        Result r = run(config, tmp, "role", "get", "viewer");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("viewer"), r.out);
    }

    @Test
    void keyListJsonOutput(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond("/v1/keys/platform/", 200,
                "{\"items\":[{\"name\":\"prod-key\"}],\"hasMore\":false}");

        Result r = run(config, tmp, "-o", "json", "key", "list");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("\"prod-key\""), r.out);
    }

    @Test
    void settingsGetHitsSingletonUrl(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond("/v1/settings/platform/global", 200,
                "{\"globalInterceptors\":[],\"source\":\"file\"}");

        Result r = run(config, tmp, "-o", "json", "settings", "get");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("\"source\""), r.out);
        assertTrue(r.out.contains("\"file\""), r.out);
    }

    @Test
    void getSettingsAliasHitsSingleton(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond("/v1/settings/platform/global", 200, "{\"source\":\"default\"}");

        Result r = run(config, tmp, "-o", "yaml", "get", "settings");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("source: \"default\""), r.out);
    }

    @Test
    void getRolesAliasDispatches(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond("/v1/roles/platform/", 200,
                "{\"items\":[{\"name\":\"admin\"},{\"name\":\"viewer\"}],\"hasMore\":false}");

        Result r = run(config, tmp, "get", "roles");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("admin"), r.out);
        assertTrue(r.out.contains("viewer"), r.out);
    }

    @Test
    void schemaGetCanonicalIdPassesThroughVerbatim(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond("/v1/schemas/public/my-schema", 200, "{\"name\":\"my-schema\"}");

        Result r = run(config, tmp, "schema", "get", "schemas/public/my-schema");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("my-schema"), r.out);
    }

    @Test
    void schemaListUsesPublicBucket(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond("/v1/schemas/public/", 200,
                "{\"items\":[{\"name\":\"my-schema\"}],\"hasMore\":false}");

        Result r = run(config, tmp, "schema", "list");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("my-schema"), r.out);
    }

    @Test
    void toolsetGetAmbiguousIdRejected(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);

        Result r = run(config, tmp, "toolset", "get", "public/foo");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("Ambiguous"), r.err);
        assertTrue(r.err.contains("toolsets/public/<name>"), r.err);
    }

    @Test
    void roleGetAmbiguousIdRejectedShowsPlatformBucket(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);

        Result r = run(config, tmp, "role", "get", "platform/foo");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("Ambiguous"), r.err);
        assertTrue(r.err.contains("roles/platform/<name>"), r.err);
    }

    @Test
    void listWarnsWhenHasMoreIsTrue(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond("/v1/keys/platform/", 200,
                "{\"items\":[{\"name\":\"k1\"}],\"hasMore\":true}");

        Result r = run(config, tmp, "key", "list");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.err.contains("truncated"), r.err);
        assertTrue(r.out.contains("k1"), r.out);
    }

    @Test
    void routeListWhenEmpty(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);
        respond("/v1/routes/platform/", 200, "{\"items\":[],\"hasMore\":false}");

        Result r = run(config, tmp, "route", "list");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("NAME"), r.out);
    }

    @Test
    void getUnknownTypeRejected(@TempDir Path tmp) throws Exception {
        Path config = setup(tmp);

        Result r = run(config, tmp, "get", "frobnicators");

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

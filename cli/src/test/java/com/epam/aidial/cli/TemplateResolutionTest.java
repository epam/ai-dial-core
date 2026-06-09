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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateResolutionTest {

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

    private Path writeProfile(Path tmp, String templatesYaml, String varsYaml) throws Exception {
        Path key = tmp.resolve("key.txt");
        Files.writeString(key, "test-key");
        Path config = tmp.resolve("config.yaml");
        String content = """
                defaults: { env: dev }
                environments:
                  dev:
                    api_url: "%s"
                    auth: { type: api_key, key_env_var: NONEXISTENT_DIAL_TEST_KEY }
                    vars:
                %s
                templates:
                %s
                """.formatted(baseUrl, indent(varsYaml, 6), indent(templatesYaml, 2));
        Files.writeString(config, content);
        return config;
    }

    private static String indent(String s, int spaces) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String prefix = " ".repeat(spaces);
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            if (line.isEmpty()) {
                out.append('\n');
            } else {
                out.append(prefix).append(line).append('\n');
            }
        }
        // Drop the trailing newline we always add to keep the inserted block compact.
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
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
        CommandLine cli = DialCliFactory.build();
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

    private void recordPut(String path, int status, String body, AtomicReference<String> sink, AtomicInteger hits) {
        server.createContext(path, exchange -> {
            hits.incrementAndGet();
            sink.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, status, body);
        });
    }

    @Test
    void applyBareExtends(@TempDir Path tmp) throws Exception {
        String templates = """
                base:
                  fields:
                    foo: 1
                child:
                  extends: base
                """;
        Path config = writeProfile(tmp, templates, "");
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                template: child
                spec: {}
                """);

        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/v1/admin/validate", x -> send(x, 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}"));
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied\"}]}",
                applyBody, hits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(applyBody.get().contains("\"foo\":1"), applyBody.get());
    }

    @Test
    void applyExtendsChain(@TempDir Path tmp) throws Exception {
        String templates = """
                C:
                  fields:
                    a: from-C
                    b: from-C
                B:
                  extends: C
                  fields:
                    b: from-B
                A:
                  extends: B
                  fields:
                    c: from-A
                """;
        Path config = writeProfile(tmp, templates, "");
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                template: A
                spec: {}
                """);

        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/v1/admin/validate", x -> send(x, 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}"));
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied\"}]}",
                applyBody, hits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        // Order: C (outer-most parent) → B → A. Later wins.
        assertTrue(applyBody.get().contains("\"a\":\"from-C\""), applyBody.get());
        assertTrue(applyBody.get().contains("\"b\":\"from-B\""), applyBody.get());
        assertTrue(applyBody.get().contains("\"c\":\"from-A\""), applyBody.get());
    }

    @Test
    void applyExtendsCycleExitsTwo(@TempDir Path tmp) throws Exception {
        String templates = """
                A:
                  extends: B
                B:
                  extends: A
                """;
        Path config = writeProfile(tmp, templates, "");
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                template: A
                spec: {}
                """);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("A"), r.err);
        assertTrue(r.err.contains("B"), r.err);
    }

    @Test
    void applyIncludes(@TempDir Path tmp) throws Exception {
        String templates = """
                m1:
                  fields:
                    a: from-m1
                    b: from-m1
                m2:
                  fields:
                    b: from-m2
                    c: from-m2
                T:
                  includes: [m1, m2]
                  fields:
                    c: from-T
                """;
        Path config = writeProfile(tmp, templates, "");
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                template: T
                spec: {}
                """);

        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/v1/admin/validate", x -> send(x, 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}"));
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied\"}]}",
                applyBody, hits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(applyBody.get().contains("\"a\":\"from-m1\""), applyBody.get());
        assertTrue(applyBody.get().contains("\"b\":\"from-m2\""), applyBody.get());
        assertTrue(applyBody.get().contains("\"c\":\"from-T\""), applyBody.get());
    }

    @Test
    void applyAllSevenFunctions(@TempDir Path tmp) throws Exception {
        String templates = """
                T:
                  fields:
                    f_default: "${default(vars.MAYBE_MISSING, 'fallback')}"
                    f_lower: "${lower(entity.name)}"
                    f_upper: "${upper(entity.name)}"
                    f_trim: "${trim(vars.WITH_WS)}"
                    f_join: "${join(params.regions, '|')}"
                    f_base64: "${base64(vars.PLAIN)}"
                    f_replace: "${replace(entity.name, '-', '_')}"
                """;
        String vars = """
                WITH_WS: "  hi  "
                PLAIN: "abc"
                """;
        Path config = writeProfile(tmp, templates, vars);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/My-Name
                template: T
                params:
                  regions: [a,b,c]
                spec: {}
                """);

        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/v1/admin/validate", x -> send(x, 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/My-Name\",\"status\":\"valid\"}]}"));
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/My-Name\",\"status\":\"applied\"}]}",
                applyBody, hits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        String body = applyBody.get();
        assertTrue(body.contains("\"f_default\":\"fallback\""), body);
        assertTrue(body.contains("\"f_lower\":\"my-name\""), body);
        assertTrue(body.contains("\"f_upper\":\"MY-NAME\""), body);
        assertTrue(body.contains("\"f_trim\":\"hi\""), body);
        assertTrue(body.contains("\"f_join\":\"a|b|c\""), body);
        // base64("abc") == YWJj
        assertTrue(body.contains("\"f_base64\":\"YWJj\""), body);
        assertTrue(body.contains("\"f_replace\":\"My_Name\""), body);
    }

    @Test
    void applyAllThreePlaceholderScopes(@TempDir Path tmp) throws Exception {
        String templates = """
                T:
                  fields:
                    fromVars: "${vars.SOMETHING}"
                    fromParams: "${params.region}"
                    fromEntity: "${entity.name}"
                """;
        String vars = "SOMETHING: \"value-from-vars\"";
        Path config = writeProfile(tmp, templates, vars);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                template: T
                params:
                  region: us-east-1
                spec: {}
                """);

        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/v1/admin/validate", x -> send(x, 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}"));
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied\"}]}",
                applyBody, hits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(applyBody.get().contains("\"fromVars\":\"value-from-vars\""), applyBody.get());
        assertTrue(applyBody.get().contains("\"fromParams\":\"us-east-1\""), applyBody.get());
        assertTrue(applyBody.get().contains("\"fromEntity\":\"m\""), applyBody.get());
        assertFalse(applyBody.get().contains("${"), "no unresolved placeholders");
    }

    @Test
    void applyIfTruthy(@TempDir Path tmp) throws Exception {
        String templates = """
                T:
                  fields:
                    !if "true":
                      iconUrl: "shown"
                """;
        Path config = writeProfile(tmp, templates, "");
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                template: T
                spec: {}
                """);

        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/v1/admin/validate", x -> send(x, 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}"));
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied\"}]}",
                applyBody, hits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(applyBody.get().contains("\"iconUrl\":\"shown\""), applyBody.get());
    }

    @Test
    void applyIfFalsy(@TempDir Path tmp) throws Exception {
        String templates = """
                T:
                  fields:
                    keep: "yes"
                    !if "false":
                      iconUrl: "hidden"
                """;
        Path config = writeProfile(tmp, templates, "");
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                template: T
                spec: {}
                """);

        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/v1/admin/validate", x -> send(x, 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}"));
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied\"}]}",
                applyBody, hits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(applyBody.get().contains("\"keep\":\"yes\""), applyBody.get());
        assertFalse(applyBody.get().contains("iconUrl"), applyBody.get());
    }

    @Test
    void applyIfQuotedComparisonResolvesPlaceholdersWhenTrue(@TempDir Path tmp) throws Exception {
        // Cli.6 regression: !if "${vars.flag} == 'true'": used to always fire because
        // ExpressionEvaluator.readOperand swallowed the entire quoted block as a single
        // literal and fell through to bare-operand truthiness (non-empty → true).
        // ControlFlowExpander now strips the outer YAML quotes when they wrap the whole
        // expression so the inner comparison is parsed normally.
        String templates = """
                T:
                  fields:
                    !if "${vars.flag} == 'true'":
                      iconUrl: "shown"
                """;
        String vars = """
                flag: "true"
                """;
        Path config = writeProfile(tmp, templates, vars);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                template: T
                spec: {}
                """);

        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/v1/admin/validate", x -> send(x, 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}"));
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied\"}]}",
                applyBody, hits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(applyBody.get().contains("\"iconUrl\":\"shown\""), applyBody.get());
    }

    @Test
    void applyIfQuotedComparisonResolvesPlaceholdersWhenFalse(@TempDir Path tmp) throws Exception {
        // Cli.6 regression: pre-fix, flag="false" still surfaced the body because the
        // outer-quoted expression always evaluated truthy. Post-fix it must NOT fire.
        String templates = """
                T:
                  fields:
                    keep: "yes"
                    !if "${vars.flag} == 'true'":
                      iconUrl: "hidden"
                """;
        String vars = """
                flag: "false"
                """;
        Path config = writeProfile(tmp, templates, vars);
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                template: T
                spec: {}
                """);

        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/v1/admin/validate", x -> send(x, 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}"));
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied\"}]}",
                applyBody, hits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(applyBody.get().contains("\"keep\":\"yes\""), applyBody.get());
        assertFalse(applyBody.get().contains("iconUrl"), applyBody.get());
    }

    @Test
    void applyForZeroElement(@TempDir Path tmp) throws Exception {
        String templates = """
                T:
                  fields:
                    upstreams:
                      !for { in: "${params.regions}", as: region }:
                        - region: "${region}"
                """;
        Path config = writeProfile(tmp, templates, "");
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                template: T
                params:
                  regions: []
                spec: {}
                """);

        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/v1/admin/validate", x -> send(x, 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}"));
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied\"}]}",
                applyBody, hits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        assertTrue(applyBody.get().contains("\"upstreams\":[]"), applyBody.get());
    }

    @Test
    void applyForLoopOverNthElement(@TempDir Path tmp) throws Exception {
        String templates = """
                T:
                  fields:
                    upstreams:
                      !for { in: "${params.regions}", as: region }:
                        - region: "${region}"
                """;
        Path config = writeProfile(tmp, templates, "");
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                template: T
                params:
                  regions: [a, b, c]
                spec: {}
                """);

        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/v1/admin/validate", x -> send(x, 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}"));
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied\"}]}",
                applyBody, hits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        String body = applyBody.get();
        assertTrue(body.contains("\"region\":\"a\""), body);
        assertTrue(body.contains("\"region\":\"b\""), body);
        assertTrue(body.contains("\"region\":\"c\""), body);
    }

    @Test
    void applyMissingVarsExitsTwo(@TempDir Path tmp) throws Exception {
        String templates = """
                T:
                  fields:
                    x: "${vars.MISSING}"
                """;
        Path config = writeProfile(tmp, templates, "");
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                template: T
                spec: {}
                """);
        AtomicInteger anyHits = new AtomicInteger();
        server.createContext("/", exchange -> {
            anyHits.incrementAndGet();
            send(exchange, 500, "{}");
        });

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertEquals(0, anyHits.get(), "missing-var failure must not call the server");
        assertTrue(r.err.contains("MISSING"), r.err);
    }

    @Test
    void applyUnknownFunctionExitsTwo(@TempDir Path tmp) throws Exception {
        String templates = """
                T:
                  fields:
                    x: "${badFn(entity.name)}"
                """;
        Path config = writeProfile(tmp, templates, "");
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                template: T
                spec: {}
                """);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.toLowerCase().contains("badfn") || r.err.contains("Unknown"), r.err);
    }

    @Test
    void applyNestedPlaceholderRejectedClearly(@TempDir Path tmp) throws Exception {
        String templates = """
                T:
                  fields:
                    x: "${default(${vars.X}, 'fallback')}"
                """;
        Path config = writeProfile(tmp, templates, "");
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                template: T
                spec: {}
                """);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("Nested"), r.err);
    }

    @Test
    void applySecretMissingFailsLoud(@TempDir Path tmp) throws Exception {
        // 4C.4: ${SECRET:*} resolves at apply time via System.getenv; missing values fail
        // loud rather than passing through. The key below is never set in the test JVM,
        // so resolution must abort the apply with exit 2 and a message naming the key.
        String templates = """
                T:
                  fields:
                    apiKey: "${SECRET:dial-cli-test-secret-never-set}"
                """;
        Path config = writeProfile(tmp, templates, "");
        Path manifest = tmp.resolve("m.yaml");
        Files.writeString(manifest, """
                kind: Model
                name: models/public/m
                template: T
                spec: {}
                """);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("dial-cli-test-secret-never-set"), r.err);
    }

    @Test
    void templateExtendsResolvedBeforePostForApplyAddValidate(@TempDir Path tmp) throws Exception {
        String templates = """
                T:
                  fields:
                    endpoint: "http://${vars.host}/v1"
                    !if "${vars.flag} == 'on'":
                      forwardAuthToken: true
                    upstreams:
                      !for { in: "${params.regions}", as: region }:
                        - region: "${region}"
                """;
        String vars = """
                host: "example.com"
                flag: "on"
                """;
        Path config = writeProfile(tmp, templates, vars);

        // 1. apply path
        Path applyManifest = tmp.resolve("apply.yaml");
        Files.writeString(applyManifest, """
                kind: Model
                name: models/public/m
                template: T
                params:
                  regions: [r1]
                spec: { type: chat }
                """);
        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger applyHits = new AtomicInteger();
        AtomicReference<String> validateBody = new AtomicReference<>();
        AtomicInteger validateHits = new AtomicInteger();
        recordPost("/v1/admin/validate", 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}",
                validateBody, validateHits);
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"applied\"}]}",
                applyBody, applyHits);

        Result a = run(config, apiKeyFile(tmp), "apply", "-f", applyManifest.toString());
        assertEquals(0, a.exitCode, a.err);
        assertNotNull(applyBody.get());
        for (String s : new String[]{applyBody.get(), validateBody.get()}) {
            assertFalse(s.contains("${"), "unresolved placeholder in: " + s);
            assertFalse(s.contains("!if"), "unexpanded !if: " + s);
            assertFalse(s.contains("!for"), "unexpanded !for: " + s);
        }
    }

    @Test
    void addWithTemplateHappyPath(@TempDir Path tmp) throws Exception {
        String templates = """
                bedrock-chat:
                  fields:
                    endpoint: "http://${vars.host}/openai/deployments/${entity.name}/chat/completions"
                    upstreams:
                      !for { in: "${params.regions}", as: region }:
                        - region: "${region}"
                """;
        String vars = "host: \"dial-bedrock.dev.cluster\"";
        Path config = writeProfile(tmp, templates, vars);
        Path file = tmp.resolve("m.yaml");
        Files.writeString(file, """
                type: chat
                """);

        AtomicReference<String> postBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        recordPost("/v1/models/public/anthropic.claude-sonnet", 200, "{}", postBody, hits);

        Result r = run(config, apiKeyFile(tmp),
                "model", "add",
                "--name", "models/public/anthropic.claude-sonnet",
                "--from-file", file.toString(),
                "--template", "bedrock-chat",
                "--param", "regions=[us-east-1]");

        assertEquals(0, r.exitCode, r.err);
        assertEquals(1, hits.get());
        assertTrue(postBody.get().contains("\"endpoint\":\"http://dial-bedrock.dev.cluster"
                        + "/openai/deployments/anthropic.claude-sonnet/chat/completions\""),
                postBody.get());
        assertTrue(postBody.get().contains("\"region\":\"us-east-1\""), postBody.get());
        assertTrue(postBody.get().contains("\"type\":\"chat\""), postBody.get());
        assertFalse(postBody.get().contains("${"), "no unresolved placeholders: " + postBody.get());
        assertFalse(postBody.get().contains("!if"), "no leftover !if: " + postBody.get());
        assertFalse(postBody.get().contains("!for"), "no leftover !for: " + postBody.get());
    }

    @Test
    void addWithStringParamSubstitutedInTemplate(@TempDir Path tmp) throws Exception {
        String templates = """
                azure-chat:
                  fields:
                    endpoint: "https://${params.deployment}.openai.azure.com/openai"
                """;
        Path config = writeProfile(tmp, templates, "");
        Path file = tmp.resolve("m.yaml");
        Files.writeString(file, "type: chat");

        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        recordPut("/v1/models/public/gpt-4o", 200, "{}", capturedBody, hits);

        Result r = run(config, apiKeyFile(tmp),
                "model", "add",
                "--name", "models/public/gpt-4o",
                "--from-file", file.toString(),
                "--template", "azure-chat",
                "--param", "deployment=my-deployment");

        assertEquals(0, r.exitCode, r.err);
        assertEquals(1, hits.get());
        assertTrue(capturedBody.get().contains("\"endpoint\":\"https://my-deployment.openai.azure.com/openai\""),
                capturedBody.get());
        assertFalse(capturedBody.get().contains("${"), "no unresolved placeholders: " + capturedBody.get());
    }

    @Test
    void addWithMultipleParamsSubstituted(@TempDir Path tmp) throws Exception {
        String templates = """
                simple:
                  fields:
                    endpoint: "https://${params.host}/${params.path}"
                """;
        Path config = writeProfile(tmp, templates, "");
        Path file = tmp.resolve("m.yaml");
        Files.writeString(file, "type: chat");

        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        recordPut("/v1/models/public/m1", 200, "{}", capturedBody, hits);

        Result r = run(config, apiKeyFile(tmp),
                "model", "add",
                "--name", "models/public/m1",
                "--from-file", file.toString(),
                "--template", "simple",
                "--param", "host=api.example.com",
                "--param", "path=v1/completions");

        assertEquals(0, r.exitCode, r.err);
        assertEquals(1, hits.get());
        assertTrue(capturedBody.get().contains("\"endpoint\":\"https://api.example.com/v1/completions\""),
                capturedBody.get());
        assertFalse(capturedBody.get().contains("${"), "no unresolved placeholders: " + capturedBody.get());
    }

    @Test
    void addWithParamValueContainingEqualsSign(@TempDir Path tmp) throws Exception {
        // Only the first '=' separates key from value; subsequent '=' characters are part of the value.
        String templates = """
                with-token:
                  fields:
                    auth: "${params.token}"
                """;
        Path config = writeProfile(tmp, templates, "");
        Path file = tmp.resolve("m.yaml");
        Files.writeString(file, "type: chat");

        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        recordPut("/v1/models/public/m1", 200, "{}", capturedBody, hits);

        Result r = run(config, apiKeyFile(tmp),
                "model", "add",
                "--name", "models/public/m1",
                "--from-file", file.toString(),
                "--template", "with-token",
                "--param", "token=abc=def==");

        assertEquals(0, r.exitCode, r.err);
        assertEquals(1, hits.get());
        assertTrue(capturedBody.get().contains("\"auth\":\"abc=def==\""), capturedBody.get());
    }

    @Test
    void addWithInvalidParamFormatReturnsValidationError(@TempDir Path tmp) throws Exception {
        Path config = writeProfile(tmp, "", "");
        Path file = tmp.resolve("m.yaml");
        Files.writeString(file, "type: chat");

        Result r = run(config, apiKeyFile(tmp),
                "model", "add",
                "--name", "models/public/m1",
                "--from-file", file.toString(),
                "--param", "badparam");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("key=value"), r.err);
        assertTrue(r.err.contains("badparam"), r.err);
    }

    @Test
    void validateWithTemplateHappyPath(@TempDir Path tmp) throws Exception {
        String templates = """
                bedrock-chat:
                  fields:
                    endpoint: "http://${vars.host}/openai"
                """;
        String vars = "host: \"dial-bedrock.dev.cluster\"";
        Path config = writeProfile(tmp, templates, vars);
        Path file = tmp.resolve("m.yaml");
        Files.writeString(file, """
                type: chat
                """);

        AtomicReference<String> validateBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        recordPost("/v1/admin/validate", 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/m\",\"status\":\"valid\"}]}",
                validateBody, hits);

        Result r = run(config, apiKeyFile(tmp),
                "model", "validate",
                "--name", "models/public/m",
                "--from-file", file.toString(),
                "--template", "bedrock-chat");

        assertEquals(0, r.exitCode, r.err);
        assertEquals(1, hits.get());
        assertTrue(validateBody.get().contains("\"kind\":\"Model\""), validateBody.get());
        assertTrue(validateBody.get().contains("\"endpoint\":\"http://dial-bedrock.dev.cluster/openai\""),
                validateBody.get());
        assertFalse(validateBody.get().contains("${"), "no unresolved placeholders: " + validateBody.get());
        assertFalse(validateBody.get().contains("!if"), "no leftover !if: " + validateBody.get());
        assertFalse(validateBody.get().contains("!for"), "no leftover !for: " + validateBody.get());
    }

    @Test
    void applyBundleParameterizedNamePopulatesEntityNameForTemplate(@TempDir Path tmp) throws Exception {
        // Bug fix: when a Bundle entity name contains ${params.*}, the resolved name must be
        // placed in the entity context before template resolution so that ${entity.name} in
        // template fields evaluates to the actual name, not the raw placeholder string.
        String templates = """
                model-tpl:
                  fields:
                    endpoint: "http://${vars.host}/openai/deployments/${entity.name}/v1"
                    upstreams:
                      !for { in: "${params.regions}", as: region }:
                        - endpoint: "http://${vars.host}/${entity.name}"
                          region: "${region}"
                """;
        String vars = "host: \"api.example.com\"";
        Path config = writeProfile(tmp, templates, vars);
        Path manifest = tmp.resolve("bundle.yaml");
        Files.writeString(manifest, """
                kind: Bundle
                name: onboard-bundle
                params:
                  model_name: resolved-model
                  regions: [us-east-1, us-west-2]
                entities:
                  - kind: Model
                    name: "models/public/${params.model_name}"
                    template: model-tpl
                    spec: { type: chat }
                """);
        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicInteger applyHits = new AtomicInteger();
        server.createContext("/v1/admin/validate", x -> send(x, 200,
                "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/resolved-model\",\"status\":\"valid\"}]}"));
        recordPost("/v1/admin/apply", 200,
                "{\"applied\":1,\"failed\":0,\"results\":[{\"entityId\":\"models/public/resolved-model\",\"status\":\"applied\"}]}",
                applyBody, applyHits);

        Result r = run(config, apiKeyFile(tmp), "apply", "-f", manifest.toString());

        assertEquals(0, r.exitCode, r.err);
        assertEquals(1, applyHits.get());
        // Envelope name must be the resolved value.
        assertTrue(applyBody.get().contains("\"name\":\"resolved-model\""), applyBody.get());
        // ${entity.name} in the template must resolve to the actual name, not the raw placeholder.
        assertTrue(applyBody.get().contains(
                "\"endpoint\":\"http://api.example.com/openai/deployments/resolved-model/v1\""), applyBody.get());
        // ${entity.name} inside the !for loop body must also be consistent.
        assertTrue(applyBody.get().contains("\"http://api.example.com/resolved-model\""), applyBody.get());
        assertFalse(applyBody.get().contains("${"), "no unresolved placeholders: " + applyBody.get());
    }
}

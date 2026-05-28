package com.epam.aidial.cli;

import com.epam.aidial.cli.auth.ApiKeyResolver;
import com.epam.aidial.cli.config.CliProfile;
import com.epam.aidial.cli.config.ProfileLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvCommandTest {

    private static final String TWO_ENVS = """
            defaults:
              env: dev
            environments:
              dev:
                api_url: "https://dial-core.dev.example"
                auth: { type: api_key, key_env_var: DIAL_DEV_API_KEY }
              prod:
                api_url: "https://dial-core.prod.example"
                auth: { type: api_key, key_env_var: DIAL_PROD_API_KEY }
            """;

    private static Path writeProfile(Path tmp, String body) throws Exception {
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, body);
        return file;
    }

    private static Result run(Path config, String... extraArgs) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new DialCli())
                .setExecutionExceptionHandler(new DialExceptionHandler());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        String[] args = new String[2 + extraArgs.length];
        args[0] = "--config";
        args[1] = config.toString();
        System.arraycopy(extraArgs, 0, args, 2, extraArgs.length);
        int code = cli.execute(args);
        return new Result(code, out.toString(), err.toString());
    }

    private record Result(int exitCode, String out, String err) {
    }

    @Test
    void listPrintsEnvironmentsWithCurrentMarker(@TempDir Path tmp) throws Exception {
        Path file = writeProfile(tmp, TWO_ENVS);

        Result r = run(file, "env", "list");

        assertEquals(0, r.exitCode);
        assertTrue(r.out.contains("* dev"), r.out);
        assertTrue(r.out.contains("  prod"), r.out);
    }

    @Test
    void listMessagesWhenEmpty(@TempDir Path tmp) throws Exception {
        Path file = writeProfile(tmp, "defaults: { env: dev }\n");

        Result r = run(file, "env", "list");

        assertEquals(0, r.exitCode);
        assertTrue(r.out.contains("No environments configured."), r.out);
    }

    @Test
    void listShowsOverrideAsCurrent(@TempDir Path tmp) throws Exception {
        Path file = writeProfile(tmp, TWO_ENVS);

        Result r = run(file, "--env", "prod", "env", "list");

        assertEquals(0, r.exitCode);
        assertTrue(r.out.contains("* prod"), r.out);
        assertTrue(r.out.contains("  dev"), r.out);
    }

    @Test
    void currentPrintsDefault(@TempDir Path tmp) throws Exception {
        Path file = writeProfile(tmp, TWO_ENVS);

        Result r = run(file, "env", "current");

        assertEquals(0, r.exitCode);
        assertEquals("dev", r.out.strip());
    }

    @Test
    void currentRespectsOverride(@TempDir Path tmp) throws Exception {
        Path file = writeProfile(tmp, TWO_ENVS);

        Result r = run(file, "--env", "prod", "env", "current");

        assertEquals(0, r.exitCode);
        assertEquals("prod", r.out.strip());
    }

    @Test
    void currentExitsTwoWhenUnset(@TempDir Path tmp) throws Exception {
        Path file = writeProfile(tmp, "environments: { dev: { api_url: \"x\" } }\n");

        Result r = run(file, "env", "current");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("No environment selected"), r.err);
    }

    @Test
    void usePersistsDefault(@TempDir Path tmp) throws Exception {
        Path file = writeProfile(tmp, TWO_ENVS);

        Result r = run(file, "env", "use", "prod");

        assertEquals(0, r.exitCode);
        assertTrue(r.out.contains("Switched to environment 'prod'"), r.out);
        CliProfile reloaded = ProfileLoader.load(file);
        assertEquals("prod", reloaded.getDefaults().getEnv());
    }

    @Test
    void useExitsTwoWhenEnvUnknown(@TempDir Path tmp) throws Exception {
        Path file = writeProfile(tmp, TWO_ENVS);

        Result r = run(file, "env", "use", "staging");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("not found"), r.err);
        assertTrue(r.err.contains("dev"), r.err);
        assertTrue(r.err.contains("prod"), r.err);
        CliProfile reloaded = ProfileLoader.load(file);
        assertEquals("dev", reloaded.getDefaults().getEnv());
    }

    @Test
    void useExitsTwoWhenProfileEmpty(@TempDir Path tmp) throws Exception {
        Path file = writeProfile(tmp, "defaults: { env: dev }\n");

        Result r = run(file, "env", "use", "dev");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("not found"), r.err);
        assertFalse(r.err.contains("Available environments"), r.err);
    }

    @Test
    void checkPrintsResolvedDetailsWhenEnvVarSet(@TempDir Path tmp) throws Exception {
        Path file = writeProfile(tmp, TWO_ENVS);

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new DialCli())
                .setExecutionExceptionHandler(new DialExceptionHandler());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        injectResolver(cli, Map.of("DIAL_DEV_API_KEY", "secret"));

        int code = cli.execute("--config", file.toString(), "env", "check");

        assertEquals(0, code, err.toString());
        String body = out.toString();
        assertTrue(body.contains("Environment: dev"), body);
        assertTrue(body.contains("API URL:     https://dial-core.dev.example"), body);
        assertTrue(body.contains("Credentials: env-var ($DIAL_DEV_API_KEY)"), body);
    }

    @Test
    void checkReportsWouldPromptWhenNoCredentialSourceAvailable(@TempDir Path tmp) throws Exception {
        Path file = writeProfile(tmp, TWO_ENVS);

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new DialCli())
                .setExecutionExceptionHandler(new DialExceptionHandler());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        injectResolver(cli, Map.of());

        int code = cli.execute("--config", file.toString(), "env", "check");

        assertEquals(0, code, err.toString());
        assertTrue(out.toString().contains("would prompt"), out.toString());
    }

    @Test
    void checkExitsTwoWhenNoEnvSelected(@TempDir Path tmp) throws Exception {
        Path file = writeProfile(tmp, "environments:\n  dev:\n    api_url: \"x\"\n");

        Result r = run(file, "env", "check");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("No environment selected"), r.err);
    }

    @Test
    void checkExitsTwoWhenDefaultEnvIsStale(@TempDir Path tmp) throws Exception {
        Path file = writeProfile(tmp, """
                defaults: { env: ghost }
                environments:
                  dev:
                    api_url: "https://dial-core.dev.example"
                """);

        Result r = run(file, "env", "check");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("Environment 'ghost' not found"), r.err);
    }

    @Test
    void useExitsTwoWhenSaveFails(@TempDir Path tmp) throws Exception {
        Path readOnlyDir = tmp.resolve("readonly");
        Files.createDirectories(readOnlyDir);
        Path frozen = readOnlyDir.resolve("config.yaml");
        Files.writeString(frozen, TWO_ENVS);
        readOnlyDir.toFile().setWritable(false);

        try {
            Result r = run(frozen, "env", "use", "prod");

            assertEquals(2, r.exitCode);
            assertTrue(r.err.contains("Failed to write CLI profile"), r.err);
        } finally {
            readOnlyDir.toFile().setWritable(true);
        }
    }

    @Test
    void checkExitsTwoWhenApiUrlMissing(@TempDir Path tmp) throws Exception {
        Path file = writeProfile(tmp, """
                defaults: { env: dev }
                environments:
                  dev:
                    auth: { type: api_key, key_env_var: DIAL_KEY }
                """);

        Result r = run(file, "env", "check");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("no api_url"), r.err);
    }

    @Test
    void usePreservesCommentsBlankLinesAndCustomTags(@TempDir Path tmp) throws Exception {
        String yaml = """
                # dial-cli config
                defaults:
                  env: dev

                # environments
                environments:
                  dev:
                    api_url: "https://dial-core.dev.example"
                    auth: { type: api_key, key_env_var: DIAL_DEV_API_KEY }
                  prod:
                    api_url: "https://dial-core.prod.example"
                    auth: { type: api_key, key_env_var: DIAL_PROD_API_KEY }
                templates:
                  t:
                    fields:
                      !if ${vars.x} == 'true':
                        y: true
                """;
        Path file = writeProfile(tmp, yaml);

        Result r = run(file, "env", "use", "prod");

        assertEquals(0, r.exitCode);
        assertThat(Files.readString(file)).isEqualTo("""
                # dial-cli config
                defaults:
                  env: "prod"

                # environments
                environments:
                  dev:
                    api_url: "https://dial-core.dev.example"
                    auth: { type: api_key, key_env_var: DIAL_DEV_API_KEY }
                  prod:
                    api_url: "https://dial-core.prod.example"
                    auth: { type: api_key, key_env_var: DIAL_PROD_API_KEY }
                templates:
                  t:
                    fields:
                      !if ${vars.x} == 'true':
                        y: true
                """);
    }

    private static void injectResolver(CommandLine cli, Map<String, String> envs) {
        EnvCommand.Check check = (EnvCommand.Check) cli.getSubcommands()
                .get("env").getSubcommands().get("check").getCommand();
        check.apiKeyResolver = new ApiKeyResolver(envs::get, msg -> {
            throw new AssertionError("describeSource must not prompt");
        });
    }
}

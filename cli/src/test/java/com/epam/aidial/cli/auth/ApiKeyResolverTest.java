package com.epam.aidial.cli.auth;

import com.epam.aidial.cli.config.Auth;
import com.epam.aidial.cli.config.AuthType;
import com.epam.aidial.cli.config.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyResolverTest {

    private static Environment envWithKeyVar(String varName) {
        Auth auth = new Auth();
        auth.setType(AuthType.API_KEY);
        auth.setKeyEnvVar(varName);
        Environment env = new Environment();
        env.setAuth(auth);
        return env;
    }

    @Test
    void envVarHitWinsOverFile(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("key.txt");
        Files.writeString(file, "from-file\n");
        Map<String, String> envs = Map.of("DIAL_KEY", "from-env");

        ApiKeyResolver resolver = new ApiKeyResolver(envs::get, msg -> {
            throw new AssertionError("prompter must not run when env var resolves");
        });

        assertEquals("from-env", resolver.resolve("dev", envWithKeyVar("DIAL_KEY"), file));
    }

    @Test
    void blankEnvVarFallsThroughToFile(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("key.txt");
        Files.writeString(file, "  from-file\n");
        Map<String, String> envs = new HashMap<>();
        envs.put("DIAL_KEY", "   ");

        ApiKeyResolver resolver = new ApiKeyResolver(envs::get, msg -> {
            throw new AssertionError("prompter must not run when file resolves");
        });

        assertEquals("from-file", resolver.resolve("dev", envWithKeyVar("DIAL_KEY"), file));
    }

    @Test
    void promptUsedWhenEnvAndFileAbsent() {
        ApiKeyResolver resolver = new ApiKeyResolver(name -> null, msg -> "from-prompt");

        assertEquals("from-prompt", resolver.resolve("dev", envWithKeyVar("DIAL_KEY"), null));
    }

    @Test
    void throwsWhenNoSourceResolves() {
        ApiKeyResolver resolver = new ApiKeyResolver(name -> null, msg -> null);

        CliAuthException ex = assertThrows(CliAuthException.class, () ->
                resolver.resolve("dev", envWithKeyVar("DIAL_KEY"), null));
        assertTrue(ex.getMessage().contains("DIAL_KEY"));
        assertTrue(ex.getMessage().contains("dev"));
    }

    @Test
    void throwsWhenApiKeyFileUnreadable(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist.txt");
        ApiKeyResolver resolver = new ApiKeyResolver(name -> null, msg -> null);

        CliAuthException ex = assertThrows(CliAuthException.class, () ->
                resolver.resolve("dev", envWithKeyVar("DIAL_KEY"), missing));
        assertTrue(ex.getMessage().contains("Failed to read --api-key-file"));
    }

    @Test
    void worksWithoutAuthBlock() {
        ApiKeyResolver resolver = new ApiKeyResolver(name -> null, msg -> "prompted");
        Environment env = new Environment();

        assertEquals("prompted", resolver.resolve("dev", env, null));
    }

    @Test
    void describeSourceReportsEnvVarWhenSet() {
        Map<String, String> envs = Map.of("DIAL_KEY", "from-env");
        ApiKeyResolver resolver = new ApiKeyResolver(envs::get, msg -> {
            throw new AssertionError("describeSource must not prompt");
        });

        assertEquals("env-var ($DIAL_KEY)", resolver.describeSource(envWithKeyVar("DIAL_KEY"), null));
    }

    @Test
    void describeSourceReportsFileWhenFlagSetAndEnvBlank(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("key.txt");
        Files.writeString(file, "secret");
        ApiKeyResolver resolver = new ApiKeyResolver(name -> null, msg -> {
            throw new AssertionError("describeSource must not prompt");
        });

        String label = resolver.describeSource(envWithKeyVar("DIAL_KEY"), file);

        assertEquals("file (" + file + ")", label);
    }

    @Test
    void describeSourceFlagsUnreadableFile(@TempDir Path tmp) {
        Path missing = tmp.resolve("missing.txt");
        ApiKeyResolver resolver = new ApiKeyResolver(name -> null, msg -> {
            throw new AssertionError("describeSource must not prompt");
        });

        String label = resolver.describeSource(envWithKeyVar("DIAL_KEY"), missing);

        assertTrue(label.contains("NOT readable"), "expected unreadable marker, got: " + label);
    }

    @Test
    void describeSourceReportsWouldPromptWhenNoSourceAvailable() {
        ApiKeyResolver resolver = new ApiKeyResolver(name -> null, msg -> {
            throw new AssertionError("describeSource must not prompt");
        });

        assertEquals(
                "would prompt (no env var set, no --api-key-file)",
                resolver.describeSource(envWithKeyVar("DIAL_KEY"), null));
    }
}

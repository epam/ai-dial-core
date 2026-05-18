package com.epam.aidial.cli.config;

import com.epam.aidial.cli.OutputFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileLoaderTest {

    @AfterEach
    void resetEnvLookup() {
        ProfileLoader.envLookup = System::getenv;
    }

    @Test
    void loadsValidProfile(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, """
                defaults:
                  output: table
                  env: dev
                environments:
                  dev:
                    api_url: "https://dial-core.dev.example"
                    auth:
                      type: api_key
                      key_env_var: DIAL_DEV_API_KEY
                    vars:
                      adapter_host_bedrock: "http://dial-bedrock"
                  prod:
                    api_url: "https://dial-core.prod.example"
                    auth: { type: api_key, key_env_var: DIAL_PROD_API_KEY }
                """);

        CliProfile profile = ProfileLoader.load(file);

        assertNotNull(profile.getDefaults());
        assertEquals("dev", profile.getDefaults().getEnv());
        assertEquals(OutputFormat.TABLE, profile.getDefaults().getOutput());

        Environment dev = profile.getEnvironments().get("dev");
        assertEquals("https://dial-core.dev.example", dev.getApiUrl());
        assertEquals(AuthType.API_KEY, dev.getAuth().getType());
        assertEquals("DIAL_DEV_API_KEY", dev.getAuth().getKeyEnvVar());
        assertEquals("http://dial-bedrock", dev.getVars().get("adapter_host_bedrock"));

        Environment prod = profile.getEnvironments().get("prod");
        assertEquals("DIAL_PROD_API_KEY", prod.getAuth().getKeyEnvVar());
    }

    @Test
    void returnsEmptyProfileWhenFileMissing(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist.yaml");

        CliProfile profile = ProfileLoader.load(missing);

        assertNotNull(profile);
        assertNull(profile.getDefaults());
        assertNull(profile.getEnvironments());
    }

    @Test
    void throwsCliConfigExceptionOnMalformedYaml(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, "defaults: { env: dev\n  invalid: [unclosed\n");

        CliConfigException ex = assertThrows(CliConfigException.class, () -> ProfileLoader.load(file));
        assertTrue(ex.getMessage().contains("Failed to parse CLI profile"));
    }

    @Test
    void ignoresUnknownTopLevelKeys(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, """
                defaults: { env: dev }
                unknown_section:
                  foo: bar
                """);

        CliProfile profile = ProfileLoader.load(file);

        assertEquals("dev", profile.getDefaults().getEnv());
    }

    @Test
    void saveRoundTripsProfile(@TempDir Path tmp) throws Exception {
        Path source = tmp.resolve("source.yaml");
        Files.writeString(source, """
                defaults:
                  env: dev
                environments:
                  dev:
                    api_url: "https://dial-core.dev.example"
                    auth: { type: api_key, key_env_var: DIAL_DEV_API_KEY }
                  prod:
                    api_url: "https://dial-core.prod.example"
                    auth: { type: api_key, key_env_var: DIAL_PROD_API_KEY }
                """);
        CliProfile loaded = ProfileLoader.load(source);
        loaded.getDefaults().setEnv("prod");

        Path target = tmp.resolve("written.yaml");
        ProfileLoader.save(target, loaded);
        CliProfile reloaded = ProfileLoader.load(target);

        assertEquals("prod", reloaded.getDefaults().getEnv());
        assertEquals("https://dial-core.dev.example", reloaded.getEnvironments().get("dev").getApiUrl());
        assertEquals("DIAL_PROD_API_KEY", reloaded.getEnvironments().get("prod").getAuth().getKeyEnvVar());
    }

    @Test
    void usesDialCliConfigEnvVarWhenPathOmitted(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("env-set.yaml");
        Files.writeString(file, "defaults: { env: from-env }\n");
        ProfileLoader.envLookup = name -> "DIAL_CLI_CONFIG".equals(name) ? file.toString() : null;

        CliProfile profile = ProfileLoader.load(null);

        assertEquals("from-env", profile.getDefaults().getEnv());
    }

    @Test
    void explicitPathBeatsDialCliConfigEnvVar(@TempDir Path tmp) throws Exception {
        Path explicit = tmp.resolve("explicit.yaml");
        Files.writeString(explicit, "defaults: { env: explicit }\n");
        Path envFile = tmp.resolve("env.yaml");
        Files.writeString(envFile, "defaults: { env: from-env }\n");
        ProfileLoader.envLookup = name -> "DIAL_CLI_CONFIG".equals(name) ? envFile.toString() : null;

        CliProfile profile = ProfileLoader.load(explicit);

        assertEquals("explicit", profile.getDefaults().getEnv());
    }

    @Test
    void blankDialCliConfigFallsThroughToDefault() {
        ProfileLoader.envLookup = name -> "DIAL_CLI_CONFIG".equals(name) ? "   " : null;

        Path resolved = ProfileLoader.resolvePath(null);

        assertEquals(ProfileLoader.DEFAULT_PATH, resolved);
    }

    @Test
    void unsetDialCliConfigFallsThroughToDefault() {
        ProfileLoader.envLookup = name -> null;

        Path resolved = ProfileLoader.resolvePath(null);

        assertEquals(ProfileLoader.DEFAULT_PATH, resolved);
    }

    @Test
    void saveCreatesParentDirectoryIfMissing(@TempDir Path tmp) {
        Path nested = tmp.resolve("a").resolve("b").resolve("c").resolve("config.yaml");
        CliProfile profile = new CliProfile();
        Defaults defaults = new Defaults();
        defaults.setEnv("dev");
        profile.setDefaults(defaults);

        ProfileLoader.save(nested, profile);

        assertTrue(Files.exists(nested));
        CliProfile reloaded = ProfileLoader.load(nested);
        assertEquals("dev", reloaded.getDefaults().getEnv());
    }
}

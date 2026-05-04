package com.epam.aidial.cli.config;

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
        assertEquals("table", profile.getDefaults().getOutput());

        Environment dev = profile.getEnvironments().get("dev");
        assertEquals("https://dial-core.dev.example", dev.getApiUrl());
        assertEquals("api_key", dev.getAuth().getType());
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
}

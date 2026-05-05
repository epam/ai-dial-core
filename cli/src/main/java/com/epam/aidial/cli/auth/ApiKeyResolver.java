package com.epam.aidial.cli.auth;

import com.epam.aidial.cli.config.Auth;
import com.epam.aidial.cli.config.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/** Resolves an API key using design 06 §2.1's priority chain: env var → --api-key-file → no-echo prompt. */
public class ApiKeyResolver {

    private final Function<String, String> envLookup;
    private final PasswordPrompter prompter;

    public ApiKeyResolver() {
        this(System::getenv, PasswordPrompter.SYSTEM);
    }

    public ApiKeyResolver(Function<String, String> envLookup, PasswordPrompter prompter) {
        this.envLookup = envLookup;
        this.prompter = prompter;
    }

    public String resolve(String envName, Environment env, Path apiKeyFile) {
        Auth auth = (env != null) ? env.getAuth() : null;
        String keyEnvVar = (auth != null) ? auth.getKeyEnvVar() : null;
        if (keyEnvVar != null) {
            String fromEnv = envLookup.apply(keyEnvVar);
            if (fromEnv != null && !fromEnv.isBlank()) {
                return fromEnv;
            }
        }
        if (apiKeyFile != null) {
            try {
                return Files.readString(apiKeyFile).strip();
            } catch (IOException e) {
                throw new CliAuthException("Failed to read --api-key-file " + apiKeyFile + ": " + e.getMessage());
            }
        }
        String prompted = prompter.prompt("API key for env '" + envName + "': ");
        if (prompted != null && !prompted.isBlank()) {
            return prompted;
        }
        String missing = (keyEnvVar != null) ? keyEnvVar : "<auth.key_env_var>";
        throw new CliAuthException(
            "No API key resolved for env '" + envName + "'. Set $" + missing + ", pass --api-key-file <path>, or run from a TTY.");
    }

    public String describeSource(Environment env, Path apiKeyFile) {
        Auth auth = (env != null) ? env.getAuth() : null;
        String keyEnvVar = (auth != null) ? auth.getKeyEnvVar() : null;
        if (keyEnvVar != null) {
            String fromEnv = envLookup.apply(keyEnvVar);
            if (fromEnv != null && !fromEnv.isBlank()) {
                return "env-var ($" + keyEnvVar + ")";
            }
        }
        if (apiKeyFile != null) {
            String suffix = Files.isReadable(apiKeyFile) ? "" : " — NOT readable";
            return "file (" + apiKeyFile + ")" + suffix;
        }
        return "would prompt (no env var set, no --api-key-file)";
    }
}

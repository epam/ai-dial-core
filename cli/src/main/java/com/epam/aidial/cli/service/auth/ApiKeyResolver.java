package com.epam.aidial.cli.service.auth;

import com.epam.aidial.cli.data.Auth;
import com.epam.aidial.cli.data.Environment;
import com.epam.aidial.cli.exception.CliException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Resolves an API key via priority chain: --api-key-file → env var → no-echo prompt.
 */
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
        if (apiKeyFile != null) {
            try {
                return Files.readString(apiKeyFile).strip();
            } catch (IOException e) {
                throw CliException.validation("Failed to read --api-key-file " + apiKeyFile + ": " + e.getMessage());
            }
        }
        Auth auth = (env != null) ? env.getAuth() : null;
        String keyEnvVar = (auth != null) ? auth.getKeyEnvVar() : null;
        if (keyEnvVar != null) {
            String fromEnv = envLookup.apply(keyEnvVar);
            if (fromEnv != null && !fromEnv.isBlank()) {
                return fromEnv;
            }
        }
        String prompted = prompter.prompt("API key for env '" + envName + "': ");
        if (prompted != null && !prompted.isBlank()) {
            return prompted;
        }
        String msg = env == null
                ? "No API key resolved. Pass --api-key-file <path> or run from a TTY."
                : "No API key resolved for env '" + envName + "'. Pass --api-key-file <path>, set $"
                        + (keyEnvVar != null ? keyEnvVar : "<auth.key_env_var>") + ", or run from a TTY.";
        throw CliException.validation(msg);
    }

    public String describeSource(Environment env) {
        Auth auth = (env != null) ? env.getAuth() : null;
        String keyEnvVar = (auth != null) ? auth.getKeyEnvVar() : null;
        if (keyEnvVar != null) {
            String fromEnv = envLookup.apply(keyEnvVar);
            if (fromEnv != null && !fromEnv.isBlank()) {
                return "env-var ($" + keyEnvVar + ")";
            }
        }
        return "would prompt (no env var set)";
    }
}

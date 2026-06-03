package com.epam.aidial.cli;

import com.epam.aidial.cli.auth.ApiKeyResolver;
import com.epam.aidial.cli.auth.CliAuthException;
import com.epam.aidial.cli.config.CliProfile;
import com.epam.aidial.cli.config.Defaults;
import com.epam.aidial.cli.config.Environment;
import com.epam.aidial.cli.config.ProfileLoader;

import java.util.Map;

public final class EnvResolver {

    static ResolvedEnv resolveEnv(DialCli root) {
        return resolveEnv(root, null);
    }

    static ResolvedEnv resolveEnv(DialCli root, String explicitEnv) {
        CliProfile profile = ProfileLoader.load(root.configPath);
        boolean crossEnv = explicitEnv != null && !explicitEnv.isBlank();
        String envName = crossEnv ? explicitEnv : resolveCurrent(root, profile);
        if (envName == null || envName.isBlank()) {
            throw CliException.validation(
                    "No environment selected. Pass --env or set defaults.env via 'dial-cli env use'.");
        }
        Map<String, Environment> envs = profile.getEnvironments();
        Environment env = (envs != null) ? envs.get(envName) : null;
        if (env == null) {
            throw CliException.validation("Environment '" + envName + "' not found in profile.");
        }
        boolean useApiUrlOverride = !crossEnv && root.apiUrl != null && !root.apiUrl.isBlank();
        String apiUrl = useApiUrlOverride ? root.apiUrl : env.getApiUrl();
        if (apiUrl == null || apiUrl.isBlank()) {
            throw CliException.validation(
                    "Environment '" + envName + "' has no api_url and no --api-url override.");
        }
        if (apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }
        try {
            String apiKey = new ApiKeyResolver().resolve(envName, env, root.apiKeyFile);
            Map<String, Object> vars = (env.getVars() != null) ? env.getVars() : Map.of();
            Map<String, Object> templates = (profile.getTemplates() != null) ? profile.getTemplates() : Map.of();
            return new EnvResolver.ResolvedEnv(envName, apiUrl, apiKey, vars, templates);
        } catch (CliAuthException e) {
            throw CliException.validation(e.getMessage());
        }
    }

    static String resolveCurrent(DialCli root, CliProfile profile) {
        if (root.env != null && !root.env.isBlank()) {
            return root.env;
        }
        return resolveDefault(profile);
    }

    static String resolveDefault(CliProfile profile) {
        Defaults defaults = profile.getDefaults();
        if (defaults != null && defaults.getEnv() != null && !defaults.getEnv().isBlank()) {
            return defaults.getEnv();
        }
        return null;
    }

    record ResolvedEnv(String envName, String apiUrl, String apiKey,
                       Map<String, Object> vars, Map<String, Object> templates) {
    }

}

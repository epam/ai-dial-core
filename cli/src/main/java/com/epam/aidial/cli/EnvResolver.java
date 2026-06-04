package com.epam.aidial.cli;

import com.epam.aidial.cli.auth.ApiKeyResolver;
import com.epam.aidial.cli.config.CliProfile;
import com.epam.aidial.cli.config.Defaults;
import com.epam.aidial.cli.config.Environment;
import com.epam.aidial.cli.config.ProfileLoader;

import java.util.Map;

public final class EnvResolver {

    // package-private — replaced in tests to avoid real System.getenv / TTY lookups
    static ApiKeyResolver apiKeyResolver = new ApiKeyResolver();

    static ResolvedEnv resolveEnv(DialCli root) {
        CliProfile profile = ProfileLoader.load(root.configPath);
        String envName = resolveCurrent(root, profile);
        if (envName == null || envName.isBlank()) {
            return resolveAdHoc(root, profile);
        }
        Environment env = lookupEnv(profile, envName);
        String apiUrl = (root.apiUrl != null && !root.apiUrl.isBlank()) ? root.apiUrl : env.getApiUrl();
        String apiKey = apiKeyResolver.resolve(envName, env, root.apiKeyFile);
        return buildResolved(profile, envName, env, apiUrl, apiKey);
    }

    static ResolvedEnv resolveEnv(DialCli root, String explicitEnv) {
        CliProfile profile = ProfileLoader.load(root.configPath);
        Environment env = lookupEnv(profile, explicitEnv);
        String apiKey = apiKeyResolver.resolve(explicitEnv, env, null);
        return buildResolved(profile, explicitEnv, env, env.getApiUrl(), apiKey);
    }

    private static ResolvedEnv resolveAdHoc(DialCli root, CliProfile profile) {
        String apiUrl = root.apiUrl;
        if (apiUrl == null || apiUrl.isBlank()) {
            throw CliException.validation(
                    "No environment selected and no --api-url provided. Pass --env <name> or --api-url <url>.");
        }
        apiUrl = stripTrailingSlash(apiUrl);

        String apiKey = apiKeyResolver.resolve("<ad-hoc>", null, root.apiKeyFile);
        Map<String, Object> templates = (profile.getTemplates() != null) ? profile.getTemplates() : Map.of();
        return new ResolvedEnv("<ad-hoc>", apiUrl, apiKey, Map.of(), templates);
    }

    static Environment lookupEnv(CliProfile profile, String envName) {
        Map<String, Environment> envs = profile.getEnvironments();
        Environment env = (envs != null) ? envs.get(envName) : null;
        if (env == null) {
            throw CliException.validation("Environment '" + envName + "' not found in profile.");
        }
        return env;
    }

    private static ResolvedEnv buildResolved(CliProfile profile, String envName, Environment env, String apiUrl, String apiKey) {
        if (apiUrl == null || apiUrl.isBlank()) {
            throw CliException.validation(
                    "Environment '" + envName + "' has no api_url and no --api-url override.");
        }
        apiUrl = stripTrailingSlash(apiUrl);

        Map<String, Object> vars = (env.getVars() != null) ? env.getVars() : Map.of();
        Map<String, Object> templates = (profile.getTemplates() != null) ? profile.getTemplates() : Map.of();
        return new ResolvedEnv(envName, apiUrl, apiKey, vars, templates);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
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

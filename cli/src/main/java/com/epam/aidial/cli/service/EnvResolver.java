package com.epam.aidial.cli.service;

import com.epam.aidial.cli.data.CliProfile;
import com.epam.aidial.cli.data.Defaults;
import com.epam.aidial.cli.data.Environment;
import com.epam.aidial.cli.data.ProfileLoader;
import com.epam.aidial.cli.exception.CliException;
import com.epam.aidial.cli.service.auth.ApiKeyResolver;

import java.util.Map;

public final class EnvResolver {

    // replaced in tests to avoid real System.getenv / TTY lookups
    public static ApiKeyResolver apiKeyResolver = new ApiKeyResolver();

    public static ResolvedEnv resolveEnv(CliOptionsDto opts) {
        CliProfile profile = ProfileLoader.load(opts.configPath());
        String envName = resolveCurrent(opts, profile);
        if (envName == null || envName.isBlank()) {
            return resolveAdHoc(opts, profile);
        }
        Environment env = lookupEnv(profile, envName);
        String apiUrl = (opts.apiUrl() != null && !opts.apiUrl().isBlank()) ? opts.apiUrl() : env.getApiUrl();
        String apiKey = apiKeyResolver.resolve(envName, env, opts.apiKeyFile());
        return buildResolved(profile, envName, env, apiUrl, apiKey);
    }

    public static ResolvedEnv resolveEnv(CliOptionsDto opts, String explicitEnv) {
        CliProfile profile = ProfileLoader.load(opts.configPath());
        Environment env = lookupEnv(profile, explicitEnv);
        String apiKey = apiKeyResolver.resolve(explicitEnv, env, null);
        return buildResolved(profile, explicitEnv, env, env.getApiUrl(), apiKey);
    }

    private static ResolvedEnv resolveAdHoc(CliOptionsDto opts, CliProfile profile) {
        String apiUrl = opts.apiUrl();
        if (apiUrl == null || apiUrl.isBlank()) {
            throw CliException.validation(
                    "No environment selected and no --api-url provided. Pass --env <name> or --api-url <url>.");
        }
        apiUrl = stripTrailingSlash(apiUrl);

        String apiKey = apiKeyResolver.resolve("<ad-hoc>", null, opts.apiKeyFile());
        Map<String, Object> templates = (profile.getTemplates() != null) ? profile.getTemplates() : Map.of();
        return new ResolvedEnv("<ad-hoc>", apiUrl, apiKey, Map.of(), templates);
    }

    public static Environment lookupEnv(CliProfile profile, String envName) {
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

    public static String resolveCurrent(CliOptionsDto opts, CliProfile profile) {
        if (opts.env() != null && !opts.env().isBlank()) {
            return opts.env();
        }
        return resolveDefault(profile);
    }

    public static String resolveDefault(CliProfile profile) {
        Defaults defaults = profile.getDefaults();
        if (defaults != null && defaults.getEnv() != null && !defaults.getEnv().isBlank()) {
            return defaults.getEnv();
        }
        return null;
    }

    public record ResolvedEnv(String envName, String apiUrl, String apiKey,
                              Map<String, Object> vars, Map<String, Object> templates) {
    }
}

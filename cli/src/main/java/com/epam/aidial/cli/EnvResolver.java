package com.epam.aidial.cli;

import com.epam.aidial.cli.auth.ApiKeyResolver;
import com.epam.aidial.cli.auth.CliAuthException;
import com.epam.aidial.cli.config.CliConfigException;
import com.epam.aidial.cli.config.CliProfile;
import com.epam.aidial.cli.config.Environment;
import com.epam.aidial.cli.config.ProfileLoader;
import picocli.CommandLine;

import java.util.Map;

public final class EnvResolver {

    static ResolvedEnv resolveEnv(DialCli root, CommandLine.Model.CommandSpec spec) {
        return resolveEnv(root, spec, null);
    }

    static ResolvedEnv resolveEnv(DialCli root, CommandLine.Model.CommandSpec spec, String explicitEnv) {
        CliProfile profile;
        try {
            profile = ProfileLoader.load(root.configPath);
        } catch (CliConfigException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return null;
        }
        String envName = (explicitEnv != null && !explicitEnv.isBlank()) ? explicitEnv : root.env;
        if (envName == null || envName.isBlank()) {
            envName = (profile.getDefaults() != null) ? profile.getDefaults().getEnv() : null;
        }
        if (envName == null || envName.isBlank()) {
            spec.commandLine().getErr().println(
                    "No environment selected. Pass --env or set defaults.env via 'dial-cli env use'.");
            return null;
        }
        Map<String, Environment> envs = profile.getEnvironments();
        Environment env = (envs != null) ? envs.get(envName) : null;
        if (env == null) {
            spec.commandLine().getErr().println("Environment '" + envName + "' not found in profile.");
            return null;
        }
        boolean useApiUrlOverride = explicitEnv == null && root.apiUrl != null && !root.apiUrl.isBlank();
        String apiUrl = useApiUrlOverride ? root.apiUrl : env.getApiUrl();
        if (apiUrl == null || apiUrl.isBlank()) {
            spec.commandLine().getErr().println(
                    "Environment '" + envName + "' has no api_url and no --api-url override.");
            return null;
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
            spec.commandLine().getErr().println(e.getMessage());
            return null;
        }
    }

    record ResolvedEnv(String envName, String apiUrl, String apiKey,
                       Map<String, Object> vars, Map<String, Object> templates) {
    }

}

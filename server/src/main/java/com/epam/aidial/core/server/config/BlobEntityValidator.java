package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Lazy cross-reference validator for blob-native entities (apps, toolsets) — design 02 §4.3.
 * Pure function: checks each entity against a Config snapshot and returns warnings without
 * mutating either argument. Called from the Configuration API listing/get controllers
 * (3S.3); the chat-completion hot path is unchanged.
 *
 * <p>Dependency lookups use {@link Config#isDeploymentExists(String)} — file and
 * MergedConfigStore-managed entities. Blob-native deployments (apps/toolsets in blob
 * storage) are not visible from Config alone and may produce false-positive warnings;
 * the design accepts this tradeoff in exchange for a context-free contract.
 */
public final class BlobEntityValidator {

    private BlobEntityValidator() {
    }

    public static List<ValidationWarning> validate(Application application, Config config) {
        List<ValidationWarning> warnings = new ArrayList<>();
        appendInterceptorWarnings(application.getInterceptors(), config, warnings);
        appendSchemaWarning(application.getApplicationTypeSchemaId(), config, warnings);
        appendDependencyWarnings(application.getDependencies(), config, warnings);
        return warnings;
    }

    private static void appendInterceptorWarnings(List<String> refs, Config config, List<ValidationWarning> warnings) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        for (int i = 0; i < refs.size(); i++) {
            String ref = refs.get(i);
            if (ref == null || config.getInterceptor(ref) == null) {
                warnings.add(new ValidationWarning("interceptors[" + i + "]",
                        "Interceptor '" + ref + "' not found"));
            }
        }
    }

    private static void appendSchemaWarning(URI schemaId, Config config, List<ValidationWarning> warnings) {
        if (schemaId == null) {
            return;
        }
        if (config.getCustomApplicationSchema(schemaId) == null) {
            warnings.add(new ValidationWarning("applicationTypeSchemaId",
                    "Schema '" + schemaId + "' not found"));
        }
    }

    private static void appendDependencyWarnings(List<String> deps, Config config, List<ValidationWarning> warnings) {
        if (deps == null || deps.isEmpty()) {
            return;
        }
        for (int i = 0; i < deps.size(); i++) {
            String dep = deps.get(i);
            if (dep == null || !config.isDeploymentExists(dep)) {
                warnings.add(new ValidationWarning("dependencies[" + i + "]",
                        "Dependency '" + dep + "' not found"));
            }
        }
    }
}

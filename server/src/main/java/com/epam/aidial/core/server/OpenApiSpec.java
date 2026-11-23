package com.epam.aidial.core.server;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;

/**
 * The generated OpenAPI specification of the Core API, packed into the server resources at build time
 * (see {@code processResources} in {@code server/build.gradle}). It is read once and served anonymously.
 */
@Slf4j
public final class OpenApiSpec {

    public static final String RESOURCE = "openapi/open_api_core.yaml";

    private static volatile String content;

    private OpenApiSpec() {
    }

    /**
     * @return the specification text or {@code null} if it is not packed into the resources
     */
    @Nullable
    public static String get() {
        String result = content;
        if (result == null) {
            result = load();
            content = result;
        }
        return result;
    }

    private static String load() {
        try (InputStream stream = OpenApiSpec.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                log.warn("OpenAPI specification {} is not found in resources", RESOURCE);
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load OpenAPI specification", e);
            return null;
        }
    }
}

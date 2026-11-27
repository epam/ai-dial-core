package com.epam.aidial.core.plugin;

import java.util.List;

/** The package trees supplied by Core's Shared runtime, never by a plugin's bundled copies. */
public final class SharedPackages {

    public static final List<String> PREFIXES = List.of(
            "com.epam.aidial.core.plugin.",
            "io.vertx.core.",
            "io.netty.",
            "com.fasterxml.jackson.core.",
            "com.fasterxml.jackson.databind.",
            "com.fasterxml.jackson.annotation.",
            "org.slf4j.",
            "io.opentelemetry.api.",
            "io.opentelemetry.context.");

    private SharedPackages() {
    }

    public static boolean isSharedClass(String name) {
        return PREFIXES.stream().anyMatch(name::startsWith);
    }

    public static boolean isSharedResource(String name) {
        return PREFIXES.stream().map(prefix -> prefix.replace('.', '/')).anyMatch(name::startsWith);
    }
}

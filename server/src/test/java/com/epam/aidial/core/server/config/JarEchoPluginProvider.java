package com.epam.aidial.core.server.config;

import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Plugin provider of type {@code jar-echo}, not registered on the test classpath: a test makes Core find it by writing
 * a jar that registers it ({@link #writeJar}) into the plugins directory. Accepts any settings.
 */
public class JarEchoPluginProvider implements PluginProvider {

    public static final String TYPE = "jar-echo";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<Problem> validate(ObjectNode settings) {
        return List.of();
    }

    @Override
    public Plugin create(ObjectNode settings, Vertx vertx) {
        return new Plugin() {
        };
    }

    /**
     * Writes a jar registering the provider class {@code className}. The class itself stays on the test classpath,
     * which the plugin class loader asks first, so the jar carries the registration only.
     */
    public static void writeJar(Path jar, String className) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/services/" + PluginProvider.class.getName()));
            out.write(className.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    /**
     * The same type under another class name, so that two jars collide.
     */
    public static class SameType extends JarEchoPluginProvider {
    }

    /**
     * A provider that declares a blank type.
     */
    public static class NoType extends JarEchoPluginProvider {
        @Override
        public String type() {
            return " ";
        }
    }
}

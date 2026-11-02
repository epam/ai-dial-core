package com.epam.aidial.core.server.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginRegistryTest {

    // The plugin class loader keeps its jars open as long as the JVM runs, and Windows does not delete an open file
    @TempDir(cleanup = CleanupMode.NEVER)
    Path dir;

    @Test
    void testJarInTheDirectoryIsLoaded() throws IOException {
        JarEchoPluginProvider.writeJar(dir.resolve("jar-echo.jar"), JarEchoPluginProvider.class.getName());

        PluginRegistry registry = PluginRegistry.load(dir);

        assertInstanceOf(JarEchoPluginProvider.class, registry.get(JarEchoPluginProvider.TYPE));
        assertInstanceOf(TestEchoPluginProvider.class, registry.get(TestEchoPluginProvider.TYPE));
    }

    @Test
    void testMissingDirectoryLoadsOnlyTheClasspathProviders() {
        PluginRegistry registry = PluginRegistry.load(dir.resolve("missing"));

        assertInstanceOf(TestEchoPluginProvider.class, registry.get(TestEchoPluginProvider.TYPE));
        assertNull(registry.get(JarEchoPluginProvider.TYPE));
    }

    @Test
    void testDuplicateTypeFailsTheLoad() throws IOException {
        JarEchoPluginProvider.writeJar(dir.resolve("a.jar"), JarEchoPluginProvider.class.getName());
        JarEchoPluginProvider.writeJar(dir.resolve("b.jar"), JarEchoPluginProvider.SameType.class.getName());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(dir));

        assertTrue(error.getMessage().contains("'jar-echo'"), error.getMessage());
        assertTrue(error.getMessage().contains(JarEchoPluginProvider.SameType.class.getName()), error.getMessage());
    }

    @Test
    void testUnreadableJarFailsTheLoadNamingTheJar() throws IOException {
        Files.writeString(dir.resolve("broken.jar"), "not a jar");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(dir));

        assertTrue(error.getMessage().contains("broken.jar"), error.getMessage());
    }

    @Test
    void testSameProviderInTwoJarsFailsTheLoad() throws IOException {
        JarEchoPluginProvider.writeJar(dir.resolve("echo-1.0.jar"), JarEchoPluginProvider.class.getName());
        JarEchoPluginProvider.writeJar(dir.resolve("echo-1.1.jar"), JarEchoPluginProvider.class.getName());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(dir));

        assertTrue(error.getMessage().contains("echo-1.0.jar"), error.getMessage());
        assertTrue(error.getMessage().contains("echo-1.1.jar"), error.getMessage());
    }

    @Test
    void testBlankTypeFailsTheLoad() throws IOException {
        JarEchoPluginProvider.writeJar(dir.resolve("no-type.jar"), JarEchoPluginProvider.NoType.class.getName());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(dir));

        assertTrue(error.getMessage().contains("declares no type"), error.getMessage());
    }

    @Test
    void testMissingProviderClassFailsTheLoad() throws IOException {
        JarEchoPluginProvider.writeJar(dir.resolve("missing.jar"), "com.example.MissingProvider");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(dir));

        assertTrue(error.getMessage().contains("com.example.MissingProvider"), error.getMessage());
    }
}

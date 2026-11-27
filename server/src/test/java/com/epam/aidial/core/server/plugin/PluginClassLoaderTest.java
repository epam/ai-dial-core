package com.epam.aidial.core.server.plugin;

import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.core.plugin.SharedPackages;
import com.epam.aidial.core.server.config.JarEchoPluginProvider;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginClassLoaderTest {

    @TempDir
    Path dir;

    @Test
    void testPrivateVersionsAndCoreInvisibility() throws Exception {
        URL first = writeJar("first", "private-one", Map.of());
        URL second = writeJar("second", "private-two", Map.of());
        try (PluginClassLoader a = loader(first); PluginClassLoader b = loader(second)) {
            Class<?> firstClass = a.loadClass("com.example.Private");
            Class<?> secondClass = b.loadClass("com.example.Private");
            assertEquals("private-one", firstClass.getMethod("value").invoke(null));
            assertEquals("private-two", secondClass.getMethod("value").invoke(null));
            assertNotSame(firstClass, secondClass);
            assertThrows(ClassNotFoundException.class, () -> a.loadClass(PluginRegistry.class.getName()));
            assertThrows(ClassNotFoundException.class, () -> a.loadClass("com.google.common.collect.ImmutableList"));
            assertSame(String.class, a.loadClass("java.lang.String"));
            assertSame(javax.tools.JavaCompiler.class, a.loadClass("javax.tools.JavaCompiler"));
        }
    }

    @Test
    void testSharedIdentityAndMissingSharedNeverFallsBack() throws Exception {
        URL jar = writeJar("shared", "private", Map.of(
                "com/epam/aidial/core/plugin/Missing.class", new byte[]{0},
                "io/vertx/core/Vertx.class", new byte[]{0}));
        try (PluginClassLoader loader = loader(jar)) {
            assertSame(PluginProvider.class, loader.loadClass(PluginProvider.class.getName()));
            assertSame(Vertx.class, loader.loadClass(Vertx.class.getName()));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("com.epam.aidial.core.plugin.Missing"));
            assertNull(loader.getResource("com/epam/aidial/core/plugin/Missing.class"));
            assertTrue(Collections.list(loader.getResources("com/epam/aidial/core/plugin/Missing.class")).isEmpty());
        }
    }

    @Test
    void testPrivateResourcesAndServices() throws Exception {
        String service = "META-INF/services/" + PluginProvider.class.getName();
        URL first = writeJar("resources-a", "one", Map.of(service, "private-provider".getBytes(StandardCharsets.UTF_8),
                "private.txt", "one".getBytes(StandardCharsets.UTF_8)));
        URL second = writeJar("resources-b", "two", Map.of("other.txt", "two".getBytes(StandardCharsets.UTF_8)));
        try (PluginClassLoader a = loader(first); PluginClassLoader b = loader(second)) {
            assertEquals(1, Collections.list(a.getResources(service)).size());
            try (var input = a.getResourceAsStream(service)) {
                assertEquals("private-provider", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertNull(b.getResource(service));
            assertTrue(Collections.list(b.getResources(service)).isEmpty());
            assertNull(a.getResource("other.txt"));
            assertNull(a.getResource(PluginRegistry.class.getName().replace('.', '/') + ".class"));
            assertTrue(Collections.list(a.getResources("logback.xml")).isEmpty());
            assertSameResource(PluginProvider.class, a);
            assertTrue(a.getResource("java/lang/String.class").toString().startsWith("jrt:"));
        }
    }

    @Test
    void testCorePrivateClassCanBeBundledWithoutCoreIdentity() throws Exception {
        String name = JarEchoPluginProvider.class.getName();
        String resource = name.replace('.', '/') + ".class";
        byte[] bytes;
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            bytes = input.readAllBytes();
        }
        try (PluginClassLoader loader = loader(writeJar("core-conflict", "private", Map.of(resource, bytes)))) {
            Class<?> bundled = loader.loadClass(name);
            assertNotSame(JarEchoPluginProvider.class, bundled);
            assertSame(loader, bundled.getClassLoader());
            assertTrue(PluginProvider.class.isAssignableFrom(bundled));
        }
    }

    @Test
    void testManifestCannotBorrowUnlistedJars() throws Exception {
        URL external = writeJar("external", "outside", Map.of("outside.txt", new byte[]{1}));
        Path local = dir.resolve("local.jar");
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Class-Path", external.toExternalForm());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(local), manifest)) {
            out.putNextEntry(new JarEntry("local.txt"));
            out.closeEntry();
        }
        try (PluginClassLoader loader = loader(local.toUri().toURL())) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("com.example.Private"));
            assertNull(loader.getResource("outside.txt"));
            assertTrue(Collections.list(loader.getResources("outside.txt")).isEmpty());
        }
    }

    @Test
    void testSharedFilterDoesNotExposeHiddenResourcesOrServices() throws Exception {
        ClassLoader filter = new SharedClassLoader(getClass().getClassLoader());
        assertSame(PluginProvider.class, filter.loadClass(PluginProvider.class.getName()));
        assertSame(String.class, filter.loadClass("java.lang.String"));
        assertThrows(ClassNotFoundException.class, () -> filter.loadClass(PluginRegistry.class.getName()));
        assertThrows(ClassNotFoundException.class, () -> filter.loadClass("io.vertx.core.Missing"));
        assertNull(filter.getResource("logback.xml"));
        assertNull(filter.getResource("META-INF/services/" + PluginProvider.class.getName()));
        assertTrue(Collections.list(filter.getResources("META-INF/services/" + PluginProvider.class.getName())).isEmpty());
        assertTrue(Collections.list(filter.getResources("logback.xml")).isEmpty());
        assertSameResource(PluginProvider.class, filter);
        assertEquals(1, Collections.list(filter.getResources(PluginProvider.class.getName().replace('.', '/') + ".class")).size());
    }

    @Test
    void testMultiReleasePrivateClasses() throws Exception {
        URL version = writeJar("version", "versioned", Map.of());
        byte[] versionBytes;
        try (var jar = new java.util.jar.JarFile(Path.of(version.toURI()).toFile())) {
            versionBytes = jar.getInputStream(jar.getJarEntry("com/example/Private.class")).readAllBytes();
        }
        URL multiRelease = writeJar("multi-release", "base", Map.of(
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".getBytes(StandardCharsets.UTF_8),
                "META-INF/versions/25/com/example/Private.class", versionBytes));
        try (PluginClassLoader loader = loader(multiRelease)) {
            assertEquals("versioned", loader.loadClass("com.example.Private").getMethod("value").invoke(null));
        }
    }

    @Test
    void testExactSharedBoundaryAndVersionedEntryDetection() {
        assertEquals(9, SharedPackages.PREFIXES.size());
        for (String prefix : SharedPackages.PREFIXES) {
            assertTrue(prefix.endsWith("."));
            assertTrue(SharedPackages.isSharedClass(prefix + "Example"));
            assertTrue(SharedPackages.isSharedResource(prefix.replace('.', '/') + "Example.class"));
            assertEquals(prefix, PluginRegistry.resolveSharedPackage("META-INF/versions/25/" + prefix.replace('.', '/') + "Example.class"));
        }
        assertNull(PluginRegistry.resolveSharedPackage("io/vertx/ext/Example.class"));
        assertNull(PluginRegistry.resolveSharedPackage("io/vertx/corex/Example.class"));
        assertNull(PluginRegistry.resolveSharedPackage("io/vertx/core/example.txt"));
        assertNull(PluginRegistry.resolveSharedPackage("META-INF/versions/Example.class"));
    }

    private static void assertSameResource(Class<?> shared, ClassLoader loader) {
        String resource = shared.getName().replace('.', '/') + ".class";
        assertEquals(shared.getClassLoader().getResource(resource), loader.getResource(resource));
    }

    private PluginClassLoader loader(URL jar) {
        return new PluginClassLoader(new URL[]{jar}, getClass().getClassLoader());
    }

    private URL writeJar(String id, String value, Map<String, byte[]> resources) throws IOException {
        Path sources = Files.createDirectories(dir.resolve(id));
        Path source = Files.writeString(sources.resolve("Private.java"),
                "package com.example; public class Private { public static String value() { return \"" + value + "\"; } }");
        Path classes = sources.resolve("classes");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()));
        Path jar = sources.resolve("plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("com/example/Private.class"));
            out.write(Files.readAllBytes(classes.resolve("com/example/Private.class")));
            out.closeEntry();
            for (var entry : resources.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar.toUri().toURL();
    }
}

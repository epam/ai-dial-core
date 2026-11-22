package com.epam.aidial.core.server.plugin;

import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.core.server.config.JarEchoPluginProvider;
import com.epam.aidial.core.server.config.LegacyTypePluginProvider;
import com.epam.aidial.core.server.config.TestEchoPluginProvider;
import com.epam.aidial.core.server.config.TestOriginPluginProvider;
import com.epam.aidial.core.server.data.PluginProviderItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginRegistryTest {

    // The plugin class loaders keep their jars open as long as the JVM runs, and Windows does not delete an open file
    @TempDir(cleanup = CleanupMode.NEVER)
    Path dir;

    @Test
    void testJarInThePluginDirectoryIsLoaded() throws IOException {
        writeJar("custom/echo/jar-echo.jar", JarEchoPluginProvider.class.getName());

        PluginRegistry registry = PluginRegistry.load(settings());

        assertInstanceOf(JarEchoPluginProvider.class, registry.get(JarEchoPluginProvider.ID));
        assertInstanceOf(TestEchoPluginProvider.class, registry.get(TestEchoPluginProvider.ID));
    }

    @Test
    void testMissingDirectoryLoadsOnlyTheClasspathProviders() {
        PluginRegistry registry = PluginRegistry.load(new JsonObject().put("dir", dir.resolve("missing").toString()));

        assertInstanceOf(TestEchoPluginProvider.class, registry.get(TestEchoPluginProvider.ID));
        assertInstanceOf(TestOriginPluginProvider.class, registry.get(TestOriginPluginProvider.ID));
        assertNull(registry.get(JarEchoPluginProvider.ID));
    }

    @Test
    void testDuplicateTypeFailsTheLoadNamingBothDirectories() throws IOException {
        writeJar("builtin/echo/a.jar", JarEchoPluginProvider.class.getName());
        writeJar("custom/echo/b.jar", JarEchoPluginProvider.SameType.class.getName());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(settings()));

        assertTrue(error.getMessage().contains("'jar-echo'"), error.getMessage());
        assertTrue(error.getMessage().contains("builtin/echo"), error.getMessage());
        assertTrue(error.getMessage().contains("custom/echo"), error.getMessage());
    }

    @Test
    void testUnreadableJarFailsTheLoadNamingTheJar() throws IOException {
        Files.createDirectories(dir.resolve("custom/broken"));
        Files.writeString(dir.resolve("custom/broken/broken.jar"), "not a jar");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(settings()));

        assertTrue(error.getMessage().contains("broken.jar"), error.getMessage());
    }

    @Test
    void testSameProviderInTwoJarsFailsTheLoad() throws IOException {
        writeJar("custom/echo/echo-1.0.jar", JarEchoPluginProvider.class.getName());
        writeJar("custom/echo/echo-1.1.jar", JarEchoPluginProvider.class.getName());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(settings()));

        assertTrue(error.getMessage().contains("echo-1.0.jar"), error.getMessage());
        assertTrue(error.getMessage().contains("echo-1.1.jar"), error.getMessage());
    }

    @Test
    void testJarOutsidePluginDirectoryFailsTheLoad() throws IOException {
        writeJar("echo.jar", JarEchoPluginProvider.class.getName());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(settings()));

        assertTrue(error.getMessage().contains("echo.jar"), error.getMessage());
        assertTrue(error.getMessage().contains("<group>/<plugin>/"), error.getMessage());

        Files.delete(dir.resolve("echo.jar"));
        writeJar("custom/echo.jar", JarEchoPluginProvider.class.getName());

        error = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(settings()));

        assertTrue(error.getMessage().contains("echo.jar"), error.getMessage());
        assertTrue(error.getMessage().contains("<group>/<plugin>/"), error.getMessage());
    }

    @Test
    void testPluginDirectoryWithoutProviderFailsTheLoad() throws IOException {
        Files.createDirectories(dir.resolve("custom/empty"));
        Files.writeString(dir.resolve("custom/empty/README.txt"), "not a jar");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(settings()));

        assertTrue(error.getMessage().contains("custom/empty"), error.getMessage());

        // A library jar alone registers nothing either
        Files.delete(dir.resolve("custom/empty/README.txt"));
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(dir.resolve("custom/empty/library.jar")))) {
            out.putNextEntry(new JarEntry("library.txt"));
            out.closeEntry();
        }

        error = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(settings()));

        assertTrue(error.getMessage().contains("custom/empty") && error.getMessage().contains("registers no plugin provider"), error.getMessage());
    }

    @Test
    void testDotAndLostAndFoundDirectoriesAreIgnored() throws IOException {
        writeJar("custom/echo/jar-echo.jar", JarEchoPluginProvider.class.getName());
        Files.createDirectories(dir.resolve("lost+found"));
        Files.createDirectories(dir.resolve(".snapshot/old"));
        Files.createDirectories(dir.resolve("custom/lost+found"));
        Files.createDirectories(dir.resolve("custom/.cache"));

        PluginRegistry registry = PluginRegistry.load(settings());

        assertInstanceOf(JarEchoPluginProvider.class, registry.get(JarEchoPluginProvider.ID));
    }

    @Test
    void testEachPluginDirectoryLoadsItsOwnClasses() throws IOException {
        writeIsolatedPlugin("custom/a", "id-a");
        writeIsolatedPlugin("custom/b", "id-b");

        PluginRegistry registry = PluginRegistry.load(settings());

        // One provider class name and one helper class name in both directories: each provider's id() calls the
        // helper of its own directory
        PluginProvider a = registry.get("id-a");
        PluginProvider b = registry.get("id-b");
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a.getClass().getName(), b.getClass().getName());
        assertNotSame(a.getClass(), b.getClass());
    }

    @Test
    void testBlankTypeFailsTheLoad() throws IOException {
        writeJar("custom/no-type/no-type.jar", JarEchoPluginProvider.NoType.class.getName());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(settings()));

        assertTrue(error.getMessage().contains("declares no id"), error.getMessage());
    }

    @Test
    void testProviderOverridingOnlyTypeLoadsUnderThatId() throws IOException {
        writeJar("custom/legacy/legacy.jar", LegacyTypePluginProvider.class.getName());

        PluginRegistry registry = PluginRegistry.load(settings());

        assertInstanceOf(LegacyTypePluginProvider.class, registry.get(LegacyTypePluginProvider.ID));
    }

    @Test
    void testInvalidSettingsSchemaFailsTheLoad() throws IOException {
        writeJar("custom/bad/bad.jar", JarEchoPluginProvider.BadSchema.class.getName());

        String message = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(settings())).getMessage();

        assertTrue(message.contains("'bad'") && message.contains("custom/bad") && message.contains("settings schema"), message);
    }

    @Test
    void testSchemaOfAnotherDraftFailsTheLoad() throws IOException {
        writeJar("custom/bad/bad.jar", JarEchoPluginProvider.OtherDraft.class.getName());

        String message = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(settings())).getMessage();

        assertTrue(message.contains("'bad'") && message.contains("custom/bad") && message.contains("$schema"), message);
    }

    @Test
    void testSubschemaOfAnotherDraftFailsTheLoad() throws IOException {
        writeJar("custom/bad/bad.jar", JarEchoPluginProvider.NestedOtherDraft.class.getName());

        String message = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(settings())).getMessage();

        assertTrue(message.contains("'bad'") && message.contains("custom/bad") && message.contains("$schema"), message);
    }

    @Test
    void testRefIntoTheMetaSchemaLoads() throws IOException {
        writeJar("custom/good/good.jar", JarEchoPluginProvider.MetaSchemaPointer.class.getName());

        PluginRegistry registry = PluginRegistry.load(settings());

        assertNotNull(registry.settingsSchema("good"));
    }

    @Test
    void testDraft07NamedByItsHttpsAddressLoads() throws IOException {
        writeJar("custom/good/good.jar", JarEchoPluginProvider.HttpsDraft.class.getName());

        PluginRegistry registry = PluginRegistry.load(settings());

        assertInstanceOf(JarEchoPluginProvider.HttpsDraft.class, registry.get("good"));
        assertNotNull(registry.settingsSchema("good"));
        PluginProviderItem listed = registry.list().stream().filter(item -> item.id().equals("good")).findFirst().orElseThrow();
        assertEquals(new ObjectMapper().readTree(JarEchoPluginProvider.HttpsDraft.SCHEMA), listed.settingsSchema());
        assertEquals("custom/good", listed.directory());
    }

    @Test
    void testNoTargetsFailsTheLoad() throws IOException {
        writeJar("custom/bad/bad.jar", JarEchoPluginProvider.NoTargets.class.getName());

        String message = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(settings())).getMessage();

        assertTrue(message.contains("'bad'") && message.contains("custom/bad") && message.contains("targets"), message);
    }

    @Test
    void testListIsOrderedByIdWithTheMetadata() throws IOException {
        writeJar("custom/echo/echo.jar", JarEchoPluginProvider.class.getName());

        List<PluginProviderItem> listed = PluginRegistry.load(settings()).list();

        List<String> ids = listed.stream().map(PluginProviderItem::id).toList();
        assertEquals(ids.stream().sorted().toList(), ids);
        PluginProviderItem echo = listed.stream().filter(item -> item.id().equals(JarEchoPluginProvider.ID)).findFirst().orElseThrow();
        assertEquals("1.2.0", echo.version());
        assertEquals(JarEchoPluginProvider.DESCRIPTION, echo.description());
        PluginProviderItem classpath = listed.stream().filter(item -> item.id().equals(TestEchoPluginProvider.ID)).findFirst().orElseThrow();
        assertNull(classpath.directory());
        assertNull(classpath.version());
    }

    @Test
    void testRemoteReferenceFailsTheLoadWithNoFetch() throws IOException {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            JarEchoPluginProvider.RemoteRef.url = server.url("/schema.json").toString();
            writeJar("custom/bad/bad.jar", JarEchoPluginProvider.RemoteRef.class.getName());

            String message = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(settings())).getMessage();

            assertTrue(message.contains("'bad'") && message.contains("custom/bad") && message.contains(JarEchoPluginProvider.RemoteRef.url), message);
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test
    void testRemoteReferenceNoKeywordUsesFailsTheLoad() throws IOException {
        JarEchoPluginProvider.RemoteRef.url = "http://localhost:1/unused.json";
        writeJar("custom/bad/bad.jar", JarEchoPluginProvider.UnusedRef.class.getName());

        String message = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(settings())).getMessage();

        assertTrue(message.contains("'bad'") && message.contains("custom/bad") && message.contains("http://localhost:1/unused.json"), message);
    }

    @Test
    void testThrowingMetadataFailsTheLoad() throws IOException {
        writeJar("custom/bad/bad.jar", JarEchoPluginProvider.ThrowingSchema.class.getName());

        String message = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(settings())).getMessage();

        assertTrue(message.contains(JarEchoPluginProvider.ThrowingSchema.class.getName()) && message.contains("custom/bad"), message);
    }

    @Test
    void testMissingProviderClassFailsTheLoad() throws IOException {
        writeJar("custom/missing/missing.jar", "com.example.MissingProvider");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(settings()));

        assertTrue(error.getMessage().contains("com.example.MissingProvider"), error.getMessage());
    }

    @Test
    void testDefaultLoadsEveryPluginDirectory() throws IOException {
        writeJar("builtin/echo/echo.jar", JarEchoPluginProvider.class.getName());
        writeJar("custom/other/other.jar", OtherPluginProvider.class.getName());

        PluginRegistry registry = PluginRegistry.load(settings());

        assertInstanceOf(JarEchoPluginProvider.class, registry.get(JarEchoPluginProvider.ID));
        assertInstanceOf(OtherPluginProvider.class, registry.get(OtherPluginProvider.ID));
    }

    @Test
    void testExcludedGroupIsNotLoaded() throws IOException {
        writeJar("builtin/echo/echo.jar", JarEchoPluginProvider.class.getName());
        writeJar("custom/other/other.jar", OtherPluginProvider.class.getName());

        // The string form, as an environment variable gives it
        PluginRegistry registry = PluginRegistry.load(settings().put("exclude", "builtin/*"));

        assertNull(registry.get(JarEchoPluginProvider.ID));
        assertInstanceOf(OtherPluginProvider.class, registry.get(OtherPluginProvider.ID));
    }

    @Test
    void testCommaSeparatedPatternsAreEachApplied() throws IOException {
        writeJar("builtin/echo/echo.jar", JarEchoPluginProvider.class.getName());
        writeJar("custom/other/other.jar", OtherPluginProvider.class.getName());

        PluginRegistry registry = PluginRegistry.load(settings().put("exclude", "builtin/echo, custom/other,"));

        assertNull(registry.get(JarEchoPluginProvider.ID));
        assertNull(registry.get(OtherPluginProvider.ID));
        assertInstanceOf(TestEchoPluginProvider.class, registry.get(TestEchoPluginProvider.ID));
    }

    @Test
    void testIncludeListLimitsTheLoad() throws IOException {
        writeJar("builtin/echo/echo.jar", JarEchoPluginProvider.class.getName());
        writeJar("custom/other/other.jar", OtherPluginProvider.class.getName());

        // Array items are stripped and blank ones dropped, as in the string form
        PluginRegistry registry = PluginRegistry.load(settings().put("include", new JsonArray().add(" custom/* ").add("")));

        assertNull(registry.get(JarEchoPluginProvider.ID));
        assertInstanceOf(OtherPluginProvider.class, registry.get(OtherPluginProvider.ID));
    }

    @Test
    void testEmptyIncludeFailsTheLoad() {
        for (Object include : List.of("", " , ", new JsonArray(), new JsonArray().add(" "))) {
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> PluginRegistry.load(settings().put("include", include)));

            assertTrue(error.getMessage().contains("plugins.include is empty"), error.getMessage());
        }
    }

    @Test
    void testExcludedBuiltInIsReplacedByMountedBuild() throws IOException {
        writeJar("builtin/echo/echo.jar", JarEchoPluginProvider.SameType.class.getName());
        writeJar("custom/echo/echo.jar", JarEchoPluginProvider.class.getName());

        PluginRegistry registry = PluginRegistry.load(settings().put("exclude", new JsonArray().add("builtin/echo")));

        assertSame(JarEchoPluginProvider.class, registry.get(JarEchoPluginProvider.ID).getClass());
    }

    @Test
    void testExcludedBrokenPluginDirectoryIsNotOpened() throws IOException {
        Files.createDirectories(dir.resolve("custom/broken"));
        Files.writeString(dir.resolve("custom/broken/broken.jar"), "not a jar");

        PluginRegistry registry = PluginRegistry.load(settings().put("exclude", new JsonArray().add("custom/broken")));

        assertInstanceOf(TestEchoPluginProvider.class, registry.get(TestEchoPluginProvider.ID));
    }

    @Test
    void testMalformedPatternFailsTheLoad() {
        for (String pattern : List.of("a/b/c", "builtin", "builtin/", "/echo", "built*/echo", "built in/echo", "'builtin/echo'")) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> PluginRegistry.load(settings().put("exclude", new JsonArray().add(pattern))));

            assertTrue(error.getMessage().contains("'" + pattern + "'"), error.getMessage());
        }
        IllegalStateException nullItem = assertThrows(IllegalStateException.class,
                () -> PluginRegistry.load(settings().put("exclude", new JsonArray().addNull())));

        assertTrue(nullItem.getMessage().contains("'null'"), nullItem.getMessage());
        // A list in brackets that is not JSON reaches Core as a string, as from an environment variable
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PluginRegistry.load(settings().put("exclude", "[builtin/echo, custom/old]")));

        assertTrue(error.getMessage().contains("'[builtin/echo'"), error.getMessage());
    }

    private JsonObject settings() {
        return new JsonObject().put("dir", dir.toString());
    }

    private void writeJar(String path, String className) throws IOException {
        Path jar = dir.resolve(path);
        Files.createDirectories(jar.getParent());
        JarEchoPluginProvider.writeJar(jar, className);
    }

    /**
     * Writes {@code <pluginDir>/plugin.jar} with the classes {@code com.example.Shared}, whose {@code id()} answers
     * {@code type}, and {@code com.example.IsolatedProvider}, which declares the type {@code Shared} answers. Neither
     * class is on the test classpath, so the plugin's own class loader defines them.
     */
    private void writeIsolatedPlugin(String pluginDir, String id) throws IOException {
        Path sources = Files.createTempDirectory("plugin-sources");
        Path shared = Files.writeString(sources.resolve("Shared.java"),
                "package com.example; public class Shared { public static String id() { return \"%s\"; } }".formatted(id));
        Path provider = Files.writeString(sources.resolve("IsolatedProvider.java"),
                "package com.example; public class IsolatedProvider extends " + JarEchoPluginProvider.class.getName()
                        + " { @Override public String id() { return Shared.id(); } }");
        Path classes = sources.resolve("classes");
        int exitCode = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), "-cp", System.getProperty("java.class.path"), shared.toString(), provider.toString());
        assertEquals(0, exitCode);

        Path jar = dir.resolve(pluginDir).resolve("plugin.jar");
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
                Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry("META-INF/services/" + PluginProvider.class.getName()));
            out.write("com.example.IsolatedProvider".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    /**
     * A second jar-registered provider, of id {@code jar-other}.
     */
    public static class OtherPluginProvider extends JarEchoPluginProvider {

        static final String ID = "jar-other";

        @Override
        public String id() {
            return ID;
        }
    }
}

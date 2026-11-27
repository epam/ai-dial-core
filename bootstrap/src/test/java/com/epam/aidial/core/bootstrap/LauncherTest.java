package com.epam.aidial.core.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LauncherTest {
    @TempDir
    Path home;

    @Test
    void startsCoreWithSharedParentAndPlatformRoot() throws Throwable {
        buildJar("shared", "fixture.Shared", "package fixture; public class Shared {}", null);
        buildJar("core", "fixture.Main", """
                package fixture;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        ClassLoader core = Main.class.getClassLoader();
                        ClassLoader shared = Shared.class.getClassLoader();
                        if (!\"dial-core\".equals(core.getName()) || core.getParent() != shared
                                || !\"dial-shared\".equals(shared.getName()) || shared.getParent() != ClassLoader.getPlatformClassLoader()
                                || Thread.currentThread().getContextClassLoader() != core) {
                            throw new AssertionError(\"Wrong loader hierarchy\");
                        }
                        try {
                            shared.loadClass(\"fixture.Main\");
                            throw new AssertionError(\"Shared sees Core\");
                        } catch (ClassNotFoundException expected) {
                        }
                        try {
                            shared.loadClass(\"com.epam.aidial.core.bootstrap.Launcher\");
                            throw new AssertionError(\"Shared sees bootstrap\");
                        } catch (ClassNotFoundException expected) {
                        }
                        System.setProperty(\"dial.bootstrap.test.argument\", args[0]);
                    }
                }
                """, home.resolve("shared/fixture.jar"));
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Launcher.start(home, "fixture.Main", new String[]{"forwarded"});
            assertEquals("forwarded", System.getProperty("dial.bootstrap.test.argument"));
            assertSame(previous, Thread.currentThread().getContextClassLoader());
        } finally {
            System.clearProperty("dial.bootstrap.test.argument");
        }
    }

    @Test
    void unwrapsStartupFailureAndRestoresContextLoader() throws Exception {
        Files.createDirectories(home.resolve("shared"));
        buildJar("core", "fixture.Main", """
                package fixture;
                public class Main {
                    public static void main(String[] args) {
                        throw new IllegalStateException("startup failed");
                    }
                }
                """, null);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> Launcher.start(home, "fixture.Main", new String[0]));
        assertEquals("startup failed", failure.getMessage());
        assertSame(previous, Thread.currentThread().getContextClassLoader());
    }

    @Test
    void rejectsMissingRuntimeDirectory() throws Exception {
        assertThrows(java.nio.file.NoSuchFileException.class, () -> Launcher.start(home, "fixture.Main", new String[0]));
    }

    @Test
    void restoresContextLoaderWhenMainClassIsMissing() throws Exception {
        Files.createDirectories(home.resolve("shared"));
        Files.createDirectories(home.resolve("core"));
        Files.writeString(home.resolve("core/not-a-jar.txt"), "ignored");
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        assertThrows(ClassNotFoundException.class, () -> Launcher.start(home, "fixture.Main", new String[0]));
        assertSame(previous, Thread.currentThread().getContextClassLoader());
    }

    private void buildJar(String side, String name, String source, Path classpath) throws Exception {
        Path compilation = Files.createTempDirectory(home, "compile-");
        Path javaFile = compilation.resolve(name.substring(name.lastIndexOf('.') + 1) + ".java");
        Files.writeString(javaFile, source);
        String[] args = classpath == null
                ? new String[]{"-d", compilation.toString(), javaFile.toString()}
                : new String[]{"-classpath", classpath.toString(), "-d", compilation.toString(), javaFile.toString()};
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args));
        Path directory = Files.createDirectories(home.resolve(side));
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(directory.resolve("fixture.jar")))) {
            String entry = name.replace('.', '/') + ".class";
            output.putNextEntry(new JarEntry(entry));
            Files.copy(compilation.resolve(entry), output);
            output.closeEntry();
        }
    }
}

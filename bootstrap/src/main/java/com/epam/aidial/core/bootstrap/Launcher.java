package com.epam.aidial.core.bootstrap;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) throws Throwable {
        Path home = Path.of(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().getParent();
        start(home, "com.epam.aidial.core.server.AiDial", args);
    }

    static void start(Path home, String mainClass, String[] args) throws Throwable {
        // Loaders live for the process lifetime: Core's main returns after starting asynchronous work.
        URLClassLoader shared = new URLClassLoader("dial-shared", listJars(home.resolve("shared")), ClassLoader.getPlatformClassLoader());
        URLClassLoader core = new URLClassLoader("dial-core", listJars(home.resolve("core")), shared);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(core);
        try {
            Class.forName(mainClass, true, core).getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static URL[] listJars(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            Path[] jars = files.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toArray(Path[]::new);
            URL[] urls = new URL[jars.length];
            for (int i = 0; i < jars.length; i++) {
                urls[i] = jars[i].toUri().toURL();
            }
            return urls;
        }
    }
}

package com.epam.aidial.core.server.config;

import com.epam.aidial.core.plugin.PluginProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * The plugin providers Core loaded at start, by the {@code type} they serve. A {@code plugins} config entry is checked
 * against the provider of its {@code type}. Providers do not reload while Core runs: new plugin code arrives with a
 * new rollout.
 */
@Slf4j
public final class PluginRegistry {

    public static final PluginRegistry EMPTY = new PluginRegistry(Map.of());

    private static final String SERVICES_ENTRY = "META-INF/services/" + PluginProvider.class.getName();

    private final Map<String, PluginProvider> providers;

    PluginRegistry(Map<String, PluginProvider> providers) {
        this.providers = providers;
    }

    /**
     * Finds the providers on Core's classpath and in the jars of {@code dir}. A missing directory adds no jars.
     *
     * @throws IllegalStateException when a jar cannot be opened, two jars register the same provider class, a provider
     *                               cannot be created, or two providers declare the same type
     */
    public static PluginRegistry load(Path dir) {
        List<URL> jars = listJars(dir);
        ClassLoader parent = PluginRegistry.class.getClassLoader();
        // ponytail: one loader for all jars, parent first, never closed since providers live as long as Core.
        // Give each jar its own loader once two plugins need conflicting versions of one library.
        ClassLoader loader = jars.isEmpty() ? parent : new URLClassLoader(jars.toArray(URL[]::new), parent);
        Map<String, PluginProvider> providers = new HashMap<>();
        try {
            for (PluginProvider provider : ServiceLoader.load(PluginProvider.class, loader)) {
                String type = provider.type();
                if (type == null || type.isBlank()) {
                    throw new IllegalStateException("Plugin provider " + provider.getClass().getName() + " declares no type");
                }
                PluginProvider previous = providers.putIfAbsent(type, provider);
                if (previous != null) {
                    throw new IllegalStateException("Plugin providers %s and %s both declare type '%s'"
                            .formatted(previous.getClass().getName(), provider.getClass().getName(), type));
                }
                log.info("Loaded plugin provider {} for type '{}'", provider.getClass().getName(), type);
            }
        } catch (ServiceConfigurationError e) {
            throw new IllegalStateException("Cannot load the plugin providers of " + dir.toAbsolutePath() + ": " + e.getMessage(), e);
        }
        return new PluginRegistry(Map.copyOf(providers));
    }

    @Nullable
    public PluginProvider get(String type) {
        return providers.get(type);
    }

    private static List<URL> listJars(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<URL> jars = new ArrayList<>();
        // ServiceLoader loads a provider class registered by two jars once, from the first jar: an old jar left beside
        // its new version would win without a word
        Map<String, Path> registeredBy = new HashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path jar : files.filter(file -> file.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                // URLClassLoader skips a jar it cannot open without a word: open each one here so a broken jar stops the start
                try (JarFile file = new JarFile(jar.toFile())) {
                    for (String provider : providerNames(file)) {
                        Path other = registeredBy.putIfAbsent(provider, jar);
                        if (other != null) {
                            throw new IllegalStateException("Plugin jars %s and %s both register %s"
                                    .formatted(other.toAbsolutePath(), jar.toAbsolutePath(), provider));
                        }
                    }
                    jars.add(jar.toUri().toURL());
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot open plugin jar " + jar.toAbsolutePath() + ": " + e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the plugins directory " + dir.toAbsolutePath(), e);
        }
        return jars;
    }

    private static List<String> providerNames(JarFile jar) throws IOException {
        JarEntry entry = jar.getJarEntry(SERVICES_ENTRY);
        if (entry == null) {
            return List.of();
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
            // The ServiceLoader file format: one class name per line, '#' starts a comment
            return reader.lines().map(line -> line.replaceFirst("#.*", "").strip()).filter(name -> !name.isEmpty()).distinct().toList();
        }
    }
}

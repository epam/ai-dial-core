package com.epam.aidial.core.server.plugin;

import com.epam.aidial.core.plugin.PluginProvider;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
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
    // * or <group>/<plugin>, where either part may be *. A name refuses whitespace, brackets, quotes and commas, so a
    // list written as [a/b] or with a stray space stops the start instead of matching nothing
    private static final String NAME = "(\\*|[^/*\\s\\[\\]\"',]+)";
    private static final Pattern PATTERN = Pattern.compile("\\*|" + NAME + "/" + NAME);
    // Created by mkfs and by volume mounts, not by a person: never a plugin directory
    private static final String LOST_AND_FOUND = "lost+found";

    private final Map<String, PluginProvider> providers;

    @VisibleForTesting
    public PluginRegistry(Map<String, PluginProvider> providers) {
        this.providers = providers;
    }

    /**
     * Finds the providers on Core's classpath and in the plugin directories {@code <dir>/<group>/<plugin>} that
     * {@code include} names and {@code exclude} does not, each plugin directory through a class loader of its own. A
     * missing directory adds none.
     *
     * @param settings the static settings {@code plugins}: {@code dir}, {@code include} and {@code exclude}
     * @throws IllegalStateException when a pattern is malformed, a jar lies outside a plugin directory, a jar cannot be
     *                               opened, two jars of one plugin directory register the same provider class, a
     *                               provider cannot be created, a loaded plugin directory registers no provider, or two
     *                               providers declare the same type
     */
    public static PluginRegistry load(JsonObject settings) {
        Path dir = Path.of(settings.getString("dir", "plugins"));
        List<String> include = patterns(settings, "include", "*");
        if (include.isEmpty()) {
            // Most likely an empty environment variable: it would load no plugin directory and log each as excluded
            throw new IllegalStateException("plugins.include is empty: name the plugin directories to load, or set plugins.exclude to * to load none");
        }
        List<String> exclude = patterns(settings, "exclude", "");
        ClassLoader parent = PluginRegistry.class.getClassLoader();
        Map<String, PluginProvider> providers = new HashMap<>();
        Map<String, String> originByType = new HashMap<>();
        addProviders(ServiceLoader.load(PluginProvider.class, parent).stream(), "the classpath", providers, originByType);
        for (Path pluginDir : listPluginDirs(dir)) {
            String group = pluginDir.getParent().getFileName().toString();
            String plugin = pluginDir.getFileName().toString();
            String id = group + "/" + plugin;
            if (!matchesAny(include, group, plugin) || matchesAny(exclude, group, plugin)) {
                log.info("Excluded plugin directory {}", id);
                continue;
            }
            PluginJars jars = listJars(pluginDir);
            if (jars.providerClasses().isEmpty()) {
                throw new IllegalStateException("Plugin directory %s (%s) registers no plugin provider".formatted(id, pluginDir.toAbsolutePath()));
            }
            // Parent first, so Core's classes win over a plugin's copy; never closed since providers live as long as
            // Core. The loader also finds the classpath's providers through its parent: keep the ones its jars register.
            // Each registered class is created or fails the load, so types is never empty here
            ClassLoader loader = new URLClassLoader(jars.urls().toArray(URL[]::new), parent);
            List<String> types = addProviders(ServiceLoader.load(PluginProvider.class, loader).stream()
                            .filter(provider -> jars.providerClasses().contains(provider.type().getName())),
                    "plugin directory " + id, providers, originByType);
            log.info("Loaded plugin directory {}: types {}", id, types);
        }
        return new PluginRegistry(Map.copyOf(providers));
    }

    @Nullable
    public PluginProvider get(String type) {
        return providers.get(type);
    }

    private static List<String> addProviders(Stream<ServiceLoader.Provider<PluginProvider>> found, String origin,
                                             Map<String, PluginProvider> providers, Map<String, String> originByType) {
        List<String> types = new ArrayList<>();
        try {
            for (PluginProvider provider : found.map(ServiceLoader.Provider::get).toList()) {
                String type = provider.type();
                if (type == null || type.isBlank()) {
                    throw new IllegalStateException("Plugin provider %s of %s declares no type".formatted(provider.getClass().getName(), origin));
                }
                PluginProvider previous = providers.putIfAbsent(type, provider);
                if (previous != null) {
                    throw new IllegalStateException("Plugin providers %s of %s and %s of %s both declare type '%s'".formatted(
                            previous.getClass().getName(), originByType.get(type), provider.getClass().getName(), origin, type));
                }
                originByType.put(type, origin);
                types.add(type);
                log.info("Loaded plugin provider {} for type '{}'", provider.getClass().getName(), type);
            }
        } catch (ServiceConfigurationError e) {
            throw new IllegalStateException("Cannot load the plugin providers of " + origin + ": " + e.getMessage(), e);
        }
        return types;
    }

    /**
     * A JSON array of patterns, or one string of comma-separated patterns, as an environment variable gives it. Both
     * forms strip each pattern and drop the blank ones.
     */
    private static List<String> patterns(JsonObject settings, String key, String defaultValue) {
        Object value = settings.getValue(key);
        Stream<String> items = value instanceof JsonArray array
                ? array.stream().map(String::valueOf)
                : Arrays.stream(String.valueOf(value == null ? defaultValue : value).split(","));
        List<String> patterns = items.map(String::strip).filter(pattern -> !pattern.isEmpty()).toList();
        for (String pattern : patterns) {
            if (!PATTERN.matcher(pattern).matches()) {
                throw new IllegalStateException("Malformed pattern '%s' in plugins.%s: use * or <group>/<plugin>, where either part may be *"
                        .formatted(pattern, key));
            }
        }
        return patterns;
    }

    private static boolean matchesAny(List<String> patterns, String group, String plugin) {
        List<String> matching = List.of("*", "*/*", group + "/*", "*/" + plugin, group + "/" + plugin);
        return patterns.stream().anyMatch(matching::contains);
    }

    /**
     * The plugin directories {@code <dir>/<group>/<plugin>}, in name order. Other files, directories whose name starts
     * with a dot and {@code lost+found} are ignored, a jar is refused.
     */
    private static List<Path> listPluginDirs(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> pluginDirs = new ArrayList<>();
        for (Path group : listSorted(dir)) {
            if (!Files.isDirectory(group)) {
                refuseJar(group);
                continue;
            }
            if (isIgnored(group)) {
                continue;
            }
            for (Path plugin : listSorted(group)) {
                if (!Files.isDirectory(plugin)) {
                    refuseJar(plugin);
                } else if (!isIgnored(plugin)) {
                    pluginDirs.add(plugin);
                }
            }
        }
        return pluginDirs;
    }

    private static boolean isIgnored(Path directory) {
        String name = directory.getFileName().toString();
        return name.startsWith(".") || name.equals(LOST_AND_FOUND);
    }

    private static void refuseJar(Path file) {
        if (isJar(file)) {
            // The flat layout of an older Core: ignoring the jar would start Core without the plugin
            throw new IllegalStateException("Plugin jar %s lies outside a plugin directory: put each plugin's jars in <plugins.dir>/<group>/<plugin>/"
                    .formatted(file.toAbsolutePath()));
        }
    }

    private static PluginJars listJars(Path pluginDir) {
        List<URL> jars = new ArrayList<>();
        // ServiceLoader loads a provider class registered by two jars once, from the first jar: an old jar left beside
        // its new version would win without a word
        Map<String, Path> registeredBy = new HashMap<>();
        for (Path jar : listSorted(pluginDir).stream().filter(PluginRegistry::isJar).toList()) {
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
        return new PluginJars(jars, registeredBy.keySet());
    }

    private static List<Path> listSorted(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the plugins directory " + dir.toAbsolutePath(), e);
        }
    }

    private static boolean isJar(Path file) {
        return file.getFileName().toString().endsWith(".jar");
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

    /**
     * The jars of one plugin directory, and the provider classes they register.
     */
    private record PluginJars(List<URL> urls, Set<String> providerClasses) {
    }
}

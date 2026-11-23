package com.epam.aidial.core.server.plugin;

import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.core.plugin.PluginTarget;
import com.epam.aidial.core.server.data.PluginProviderItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.AllowSchemaLoader;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

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
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * The plugin providers Core loaded at start, by the {@link PluginProvider#id() id} they serve, with what each declared
 * about itself at start. A {@code plugins} config entry is checked against the provider its {@code pluginId} names.
 * Providers do not reload while Core runs: new plugin code arrives with a new rollout.
 */
@Slf4j
public final class PluginRegistry {

    private static final String SERVICES_ENTRY = "META-INF/services/" + PluginProvider.class.getName();
    // * or <group>/<plugin>, where either part may be *. A name refuses whitespace, brackets, quotes and commas, so a
    // list written as [a/b] or with a stray space stops the start instead of matching nothing
    private static final String NAME = "(\\*|[^/*\\s\\[\\]\"',]+)";
    private static final Pattern PATTERN = Pattern.compile("\\*|" + NAME + "/" + NAME);
    // Created by mkfs and by volume mounts, not by a person: never a plugin directory
    private static final String LOST_AND_FOUND = "lost+found";
    // The $schema values of draft-07, which the factory maps to the meta-schema in the validator jar
    private static final Set<String> DRAFT_07 = Set.of("http://json-schema.org/draft-07/schema", "http://json-schema.org/draft-07/schema#",
            "https://json-schema.org/draft-07/schema", "https://json-schema.org/draft-07/schema#");
    private static final String META_SCHEMA = "classpath:draft-07/schema";
    // The factory maps the json-schema.org addresses to classpath: before it asks a loader, so the meta-schema still
    // loads; anything else a settings schema refers to throws instead of being fetched
    private static final JsonSchemaFactory SCHEMA_FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7,
            builder -> builder.schemaLoaders(loaders -> loaders.add(new AllowSchemaLoader(iri -> iri.toString().equals(META_SCHEMA)))));
    private static final JsonSchema DRAFT_07_META_SCHEMA = SCHEMA_FACTORY.getSchema(SchemaLocation.of(META_SCHEMA));
    // Their values are data, not schemas: a $ref or $schema in a default is a value
    private static final Set<String> DATA_KEYWORDS = Set.of("default", "const", "enum", "examples");
    // They map names to schemas: a property named default or enum is a schema, not a keyword
    private static final Set<String> NAME_MAPS = Set.of("properties", "patternProperties", "definitions", "dependencies");

    // after the schema statics: a registry built in a static initializer compiles schemas with them
    public static final PluginRegistry EMPTY = new PluginRegistry(List.of());

    private final Map<String, Entry> entries;
    private final List<PluginProviderItem> listed;

    /**
     * A registry of the given providers, as if they were on Core's classpath: each goes through the checks of a load.
     */
    @VisibleForTesting
    public PluginRegistry(List<PluginProvider> providers) {
        this(describeAll(providers));
    }

    private PluginRegistry(Map<String, Entry> entries) {
        this.entries = Map.copyOf(entries);
        this.listed = entries.values().stream().sorted(Comparator.comparing(Entry::id)).map(Entry::item).toList();
    }

    private static Map<String, Entry> describeAll(List<PluginProvider> providers) {
        Map<String, Entry> entries = new HashMap<>();
        for (PluginProvider provider : providers) {
            add(describe(provider, null, null), entries);
        }
        return entries;
    }

    /**
     * Finds the providers on Core's classpath and in the plugin directories {@code <dir>/<group>/<plugin>} that
     * {@code include} names and {@code exclude} does not, each plugin directory through a class loader of its own. A
     * missing directory adds none.
     *
     * @param settings the static settings {@code plugins}: {@code dir}, {@code include} and {@code exclude}
     * @throws IllegalStateException when a pattern is malformed, a jar lies outside a plugin directory, a jar cannot be
     *                               opened, two jars of one plugin directory register the same provider class, a
     *                               provider cannot be created, a loaded plugin directory registers no provider, two
     *                               providers declare the same id, a provider declares a settings schema Core refuses,
     *                               or reading what a provider declares throws
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
        Map<String, Entry> entries = new HashMap<>();
        addProviders(ServiceLoader.load(PluginProvider.class, parent).stream(), null, Map.of(), entries);
        for (Path pluginDir : listPluginDirs(dir)) {
            String group = pluginDir.getParent().getFileName().toString();
            String plugin = pluginDir.getFileName().toString();
            String directory = group + "/" + plugin;
            if (!matchesAny(include, group, plugin) || matchesAny(exclude, group, plugin)) {
                log.info("Excluded plugin directory {}", directory);
                continue;
            }
            PluginJars jars = listJars(pluginDir);
            if (jars.providerClasses().isEmpty()) {
                throw new IllegalStateException("Plugin directory %s (%s) registers no plugin provider".formatted(directory, pluginDir.toAbsolutePath()));
            }
            // Parent first, so Core's classes win over a plugin's copy; never closed since providers live as long as
            // Core. The loader also finds the classpath's providers through its parent: keep the ones its jars register.
            // Each registered class is created or fails the load, so ids is never empty here
            ClassLoader loader = new URLClassLoader(jars.urls().toArray(URL[]::new), parent);
            List<String> ids = addProviders(ServiceLoader.load(PluginProvider.class, loader).stream()
                            .filter(provider -> jars.providerClasses().contains(provider.type().getName())),
                    directory, jars.versions(), entries);
            log.info("Loaded plugin directory {}: ids {}", directory, ids);
        }
        return new PluginRegistry(entries);
    }

    @Nullable
    public PluginProvider get(String id) {
        Entry entry = entries.get(id);
        return entry == null ? null : entry.provider();
    }

    /**
     * The settings schema of the provider of {@code id}, compiled at start, or null when it declares none or is not
     * loaded. Thread-safe, and it loads nothing.
     */
    @Nullable
    public JsonSchema settingsSchema(String id) {
        Entry entry = entries.get(id);
        return entry == null ? null : entry.compiledSchema();
    }

    /**
     * The loaded providers ordered by id, with what each declared at start. Calls no plugin code.
     */
    public List<PluginProviderItem> list() {
        return listed;
    }

    /**
     * @param versions the {@code Implementation-Version} of the jar that registers each provider class, by class name
     */
    private static List<String> addProviders(Stream<ServiceLoader.Provider<PluginProvider>> found, @Nullable String directory,
                                             Map<String, String> versions, Map<String, Entry> entries) {
        List<String> ids = new ArrayList<>();
        try {
            for (PluginProvider provider : found.map(ServiceLoader.Provider::get).toList()) {
                Entry entry = describe(provider, directory, versions.get(provider.getClass().getName()));
                add(entry, entries);
                ids.add(entry.id());
                log.info("Loaded plugin provider {} for id '{}'", provider.getClass().getName(), entry.id());
            }
        } catch (ServiceConfigurationError e) {
            throw new IllegalStateException("Cannot load the plugin providers of " + origin(directory) + ": " + e.getMessage(), e);
        }
        return ids;
    }

    private static void add(Entry entry, Map<String, Entry> entries) {
        Entry previous = entries.putIfAbsent(entry.id(), entry);
        if (previous != null) {
            throw new IllegalStateException("Plugin providers %s of %s and %s of %s both declare id '%s'".formatted(
                    previous.provider().getClass().getName(), origin(previous.directory()),
                    entry.provider().getClass().getName(), origin(entry.directory()), entry.id()));
        }
    }

    /**
     * Reads what the provider declares, once: every call into plugin code at start is here, and Core keeps copies, so a
     * provider that changes what it returned later changes nothing.
     */
    private static Entry describe(PluginProvider provider, @Nullable String directory, @Nullable String version) {
        String name = provider.getClass().getName();
        String id = null;
        ObjectNode schema;
        String description;
        EnumSet<PluginTarget> targets = EnumSet.noneOf(PluginTarget.class);
        boolean nullTarget = false;
        try {
            id = provider.id();
            ObjectNode declared = provider.settingsSchema();
            schema = declared == null ? null : declared.deepCopy();
            description = provider.description();
            Set<PluginTarget> declaredTargets = provider.targets();
            if (declaredTargets != null) {
                for (PluginTarget target : declaredTargets) {
                    nullTarget |= target == null;
                    if (target != null) {
                        targets.add(target);
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // plugin code: a jar missing a class throws NoClassDefFoundError on first use
            throw new IllegalStateException("Plugin provider %s%s of %s fails to declare itself: %s"
                    .formatted(name, id == null ? "" : " (id '" + id + "')", origin(directory), e), e);
        }
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("Plugin provider %s of %s declares no id".formatted(name, origin(directory)));
        }
        String provided = "Plugin provider %s of %s, id '%s',".formatted(name, origin(directory), id);
        if (targets.isEmpty() || nullTarget) {
            throw new IllegalStateException("%s declares %s: declare the places a plugin url may name its entries, or keep the default, all of them"
                    .formatted(provided, nullTarget ? "a null in its targets" : "no targets"));
        }
        JsonSchema compiled = schema == null ? null : compile(schema, provided);
        return new Entry(id, provider, directory, version, description == null || description.isBlank() ? null : description, targets, schema, compiled);
    }

    /**
     * The checks of a declared settings schema: draft-07, a {@code $ref} to nothing outside it but the draft-07
     * meta-schema, valid against that meta-schema, then compiled with every validator created, so a check never loads
     * anything.
     */
    private static JsonSchema compile(ObjectNode schema, String provided) {
        List<JsonNode> subschemas = new ArrayList<>();
        addSubschemas(schema, subschemas);
        // a subschema's draft applies to that subschema, so not only the root's. Only text is the keyword
        for (JsonNode subschema : subschemas) {
            JsonNode version = subschema.get("$schema");
            if (version != null && version.isTextual() && !DRAFT_07.contains(version.asText())) {
                throw new IllegalStateException("%s declares a settings schema whose $schema is %s: only draft-07 is supported".formatted(provided, version));
            }
        }
        // Every $ref, also one no keyword reaches (under definitions nothing uses, under an unknown keyword): loading
        // follows only the refs it reaches, and the console reads the whole schema
        for (JsonNode subschema : subschemas) {
            JsonNode ref = subschema.get("$ref");
            if (ref != null && ref.isTextual() && !ref.asText().startsWith("#") && !DRAFT_07.contains(StringUtils.substringBefore(ref.asText(), "#"))) {
                throw new IllegalStateException("%s declares a settings schema with a $ref to %s: a settings schema may refer only to itself and to the draft-07 meta-schema"
                        .formatted(provided, ref.asText()));
            }
        }
        Set<ValidationMessage> problems = DRAFT_07_META_SCHEMA.validate(schema);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("%s declares a settings schema that is not valid draft-07: %s".formatted(provided,
                    problems.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; "))));
        }
        try {
            JsonSchema compiled = SCHEMA_FACTORY.getSchema(schema);
            compiled.initializeValidators();
            return compiled;
        } catch (RuntimeException e) {
            throw new IllegalStateException("%s declares a settings schema that cannot be compiled: %s".formatted(provided, e.getMessage()), e);
        }
    }

    /**
     * Adds {@code node} and every schema under it, skipping the values of {@link #DATA_KEYWORDS}. The value of an
     * unknown keyword is walked as a schema: Core cannot tell what it holds.
     */
    private static void addSubschemas(JsonNode node, List<JsonNode> subschemas) {
        if (node.isArray()) {
            node.forEach(item -> addSubschemas(item, subschemas));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        subschemas.add(node);
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (DATA_KEYWORDS.contains(field.getKey())) {
                continue;
            }
            if (NAME_MAPS.contains(field.getKey()) && field.getValue().isObject()) {
                field.getValue().forEach(named -> addSubschemas(named, subschemas));
            } else {
                addSubschemas(field.getValue(), subschemas);
            }
        }
    }

    private static String origin(@Nullable String directory) {
        return directory == null ? "the classpath" : "plugin directory " + directory;
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
        Map<String, String> versions = new HashMap<>();
        for (Path jar : listSorted(pluginDir).stream().filter(PluginRegistry::isJar).toList()) {
            // URLClassLoader skips a jar it cannot open without a word: open each one here so a broken jar stops the start
            try (JarFile file = new JarFile(jar.toFile())) {
                // The version of what the operator deployed: the jar that registers a provider, which with parent-first
                // loading need not be the jar its class comes from
                Manifest manifest = file.getManifest();
                String version = manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
                for (String provider : providerNames(file)) {
                    Path other = registeredBy.putIfAbsent(provider, jar);
                    if (other != null) {
                        throw new IllegalStateException("Plugin jars %s and %s both register %s"
                                .formatted(other.toAbsolutePath(), jar.toAbsolutePath(), provider));
                    }
                    if (version != null && !version.isBlank()) {
                        versions.put(provider, version);
                    }
                }
                jars.add(jar.toUri().toURL());
            } catch (IOException e) {
                throw new IllegalStateException("Cannot open plugin jar " + jar.toAbsolutePath() + ": " + e.getMessage(), e);
            }
        }
        return new PluginJars(jars, registeredBy.keySet(), versions);
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
     * The jars of one plugin directory, the provider classes they register, and the {@code Implementation-Version} of
     * the jar registering each class that has one.
     */
    private record PluginJars(List<URL> urls, Set<String> providerClasses, Map<String, String> versions) {
    }

    /**
     * A loaded provider and copies of what it declared at start. {@code directory} and {@code version} are null for a
     * provider on Core's classpath.
     */
    private record Entry(String id, PluginProvider provider, @Nullable String directory, @Nullable String version,
                         @Nullable String description, Set<PluginTarget> targets, @Nullable ObjectNode settingsSchema,
                         @Nullable JsonSchema compiledSchema) {

        PluginProviderItem item() {
            // In the enum's order; Locale.ROOT, since a Turkish default locale would write applıcatıon
            List<String> names = targets.stream().map(target -> target.name().toLowerCase(Locale.ROOT)).toList();
            return new PluginProviderItem(id, names, directory, version, description, settingsSchema);
        }
    }
}

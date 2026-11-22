package com.epam.aidial.core.server.config;

import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.core.plugin.PluginTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Plugin provider of id {@code jar-echo}, not registered on the test classpath: a test makes Core find it by writing
 * a jar that registers it ({@link #writeJar}) into the plugins directory. Accepts any settings, declares a description,
 * the targets {@code model} and {@code application} and the schema {@code {"type": "object"}}.
 */
public class JarEchoPluginProvider implements PluginProvider {

    public static final String ID = "jar-echo";
    public static final String DESCRIPTION = "Echoes the greeting";
    public static final String VERSION = "1.2.0";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public Set<PluginTarget> targets() {
        return EnumSet.of(PluginTarget.MODEL, PluginTarget.APPLICATION);
    }

    @Override
    public ObjectNode settingsSchema() {
        return JsonNodeFactory.instance.objectNode().put("type", "object");
    }

    @Override
    public List<Problem> validate(ObjectNode settings) {
        return List.of();
    }

    @Override
    public Plugin create(ObjectNode settings, Vertx vertx) {
        return (request, host) -> Future.failedFuture(new UnsupportedOperationException("jar-echo serves no requests"));
    }

    /**
     * Writes a jar registering the provider class {@code className}, with {@code Implementation-Version: 1.2.0} in its
     * manifest. The class itself stays on the test classpath, which the plugin class loader asks first, so the jar
     * carries the registration only.
     */
    public static void writeJar(Path jar, String className) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, VERSION);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("META-INF/services/" + PluginProvider.class.getName()));
            out.write(className.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    /**
     * The same id under another class name, so that two jars collide.
     */
    public static class SameType extends JarEchoPluginProvider {
    }

    /**
     * A provider that declares a blank id.
     */
    public static class NoType extends JarEchoPluginProvider {
        @Override
        public String id() {
            return " ";
        }
    }

    /**
     * A provider of id {@code bad} whose settings schema is {@link #schema()}, for the load errors of a schema.
     */
    public abstract static class WithSchema extends JarEchoPluginProvider {

        @Override
        public String id() {
            return "bad";
        }

        @Override
        public ObjectNode settingsSchema() {
            try {
                return (ObjectNode) new ObjectMapper().readTree(schema());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        abstract String schema();
    }

    /**
     * A schema that is not valid draft-07.
     */
    public static class BadSchema extends WithSchema {
        @Override
        String schema() {
            return "{\"type\": \"no-such-type\"}";
        }
    }

    /**
     * A schema of draft 2020-12.
     */
    public static class OtherDraft extends WithSchema {
        @Override
        String schema() {
            return "{\"$schema\": \"https://json-schema.org/draft/2020-12/schema\", \"type\": \"object\"}";
        }
    }

    public static class NestedOtherDraft extends WithSchema {
        @Override
        String schema() {
            return "{\"type\": \"object\", \"properties\": {\"a\": {\"$schema\": \"https://json-schema.org/draft/2020-12/schema\", \"type\": \"array\"}}}";
        }
    }

    /**
     * A schema whose root is a {@code $ref} to {@link #url}, which a test sets before the load.
     */
    public static class RemoteRef extends WithSchema {

        public static volatile String url;

        @Override
        String schema() {
            return "{\"$ref\": \"" + url + "\"}";
        }
    }

    /**
     * A schema with a {@code $ref} to {@link RemoteRef#url} under {@code definitions}, which nothing refers to.
     */
    public static class UnusedRef extends WithSchema {
        @Override
        String schema() {
            return "{\"type\": \"object\", \"definitions\": {\"x\": {\"$ref\": \"" + RemoteRef.url + "\"}}}";
        }
    }

    /**
     * A provider whose settings schema throws.
     */
    public static class ThrowingSchema extends WithSchema {
        @Override
        public ObjectNode settingsSchema() {
            throw new IllegalStateException("no schema today");
        }

        @Override
        String schema() {
            return "{}";
        }
    }

    /**
     * A provider of id {@code bad} that declares no targets.
     */
    public static class NoTargets extends JarEchoPluginProvider {
        @Override
        public String id() {
            return "bad";
        }

        @Override
        public Set<PluginTarget> targets() {
            return Set.of();
        }
    }

    /**
     * A provider of id {@code good} whose schema refers to a definition of the draft-07 meta-schema.
     */
    public static class MetaSchemaPointer extends WithSchema {
        @Override
        public String id() {
            return "good";
        }

        @Override
        String schema() {
            return "{\"type\": \"object\", \"properties\": {\"n\": {\"$ref\": \"http://json-schema.org/draft-07/schema#/definitions/nonNegativeInteger\"}}}";
        }
    }

    /**
     * A provider of id {@code good} whose schema names draft-07 by its https address.
     */
    public static class HttpsDraft extends WithSchema {

        public static final String SCHEMA = "{\"$schema\": \"https://json-schema.org/draft-07/schema#\", \"type\": \"object\"}";

        @Override
        public String id() {
            return "good";
        }

        @Override
        String schema() {
            return SCHEMA;
        }
    }
}

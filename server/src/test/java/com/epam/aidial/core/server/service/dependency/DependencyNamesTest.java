package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.service.dependency.DiscoveredEdge.EdgeStatus;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The names of the dependency list: the origin, the name and the key of a resource ({@link DependencyResourceNames}), and the stand-ins
 * of the entities of the file configuration ({@link FileConfigEntities}), which have no blob.
 */
public class DependencyNamesTest {

    private static final String SCHEMA_ID = "https://dial.example/schemas/s";

    private static final String CONFIG = """
            {
              "models": {"m1": {"type": "chat", "displayName": "M", "endpoint": "http://m.invalid/v1"}},
              "interceptors": {"i1": {"endpoint": "http://i.invalid", "description": "d"}},
              "toolsets": {"t1": {"endpoint": "http://t.invalid/mcp", "description": "d"}},
              "applications": {"a1": {"display_name": "A", "endpoint": "http://a.invalid/chat", "dependencies": ["m1"]}}
            }
            """;

    private final Config config = load();

    private static Config load() {
        try {
            return ProxyUtil.MAPPER.convertValue(ProxyUtil.MAPPER.readTree(CONFIG), Config.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void testOriginOfEveryLocation() {
        assertEquals("public", DependencyResourceNames.resolveOrigin(publicDescriptor(ResourceTypes.APPLICATION, "team/app")));
        assertEquals("platform", DependencyResourceNames.resolveOrigin(descriptor(ResourceTypes.MODEL, "platform", "platform/", "gpt-4o")));
        assertEquals("file", DependencyResourceNames.resolveOrigin(FileConfigEntities.newDescriptor(ResourceTypes.MODEL, "m1")));
        assertEquals("private", DependencyResourceNames.resolveOrigin(descriptor(ResourceTypes.FILE, "bucketToken", "Users/someone/", "a.txt")),
                "a user bucket is private");
    }

    @Test
    public void testNameIsTheLastSegmentOfThePathWithTheVersionLeftIn() {
        ResourceDescriptor versioned = publicDescriptor(ResourceTypes.APPLICATION, "team/bot__1.0.0");

        assertEquals("bot__1.0.0", versioned.getName());
    }

    @Test
    public void testKeyOfPlatformPublicFileAndPrivateOrigin() {
        assertEquals("application:platform:app", DependencyResourceNames.buildKey(descriptor(ResourceTypes.APPLICATION, "platform", "platform/", "app")));
        assertEquals("model:file:m", DependencyResourceNames.buildKey(FileConfigEntities.newDescriptor(ResourceTypes.MODEL, "m")));
        assertEquals("toolset:platform:search", DependencyResourceNames.buildKey(descriptor(ResourceTypes.TOOL_SET, "platform", "platform/", "search")));
        assertEquals("application:public:folder/name__1.0.0", DependencyResourceNames.buildKey(publicDescriptor(ResourceTypes.APPLICATION, "folder/name__1.0.0")));
    }

    @Test
    public void testPrivateLocationIsReportedWithoutItsBucketLocation() {
        ResourceDescriptor descriptor = descriptor(ResourceTypes.FILE, "bucketToken", "Users/someone/", "a.txt");

        assertEquals("file:private:a.txt", DependencyResourceNames.buildKey(descriptor));
        assertFalse(DependencyResourceNames.buildKey(descriptor).contains("Users"), "the location of a private bucket is not disclosed");
    }

    @Test
    public void testUnsafeCharactersAndNonAsciiBytesOfPathAreEscapedWithoutAmbiguity() {
        ResourceDescriptor colon = publicDescriptor(ResourceTypes.APPLICATION, "dir/a:b%c d.json");
        ResourceDescriptor tilde = publicDescriptor(ResourceTypes.APPLICATION, "a~b");
        ResourceDescriptor escapedTilde = publicDescriptor(ResourceTypes.APPLICATION, "a~7Eb");
        ResourceDescriptor nonAscii = publicDescriptor(ResourceTypes.APPLICATION, "é");

        assertEquals("application:public:dir/a~3Ab~25c~20d.json", DependencyResourceNames.buildKey(colon), "a colon of a path never reaches the key");
        assertEquals("application:public:a~7Eb", DependencyResourceNames.buildKey(tilde));
        assertNotEquals(DependencyResourceNames.buildKey(tilde), DependencyResourceNames.buildKey(escapedTilde), "the escape is injective");
        assertEquals("application:public:~C3~A9", DependencyResourceNames.buildKey(nonAscii));
    }

    @Test
    public void testWireNameIsLowerCaseWithHyphens() {
        assertEquals("unresolved-short-name", DependencyResourceNames.formatReason(EdgeStatus.UNRESOLVED_SHORT_NAME));
    }

    @Test
    public void testDeploymentOfIsTheLiveEntityOfItsKind() {
        assertSame(config.getModels().get("m1"),
                assertInstanceOf(Model.class, FileConfigEntities.findDeployment(config, FileConfigEntities.newDescriptor(ResourceTypes.MODEL, "m1"))));
        assertSame(config.getInterceptors().get("i1"),
                assertInstanceOf(Interceptor.class, FileConfigEntities.findDeployment(config, FileConfigEntities.newDescriptor(ResourceTypes.INTERCEPTOR, "i1"))));
        Application application = assertInstanceOf(Application.class,
                FileConfigEntities.findDeployment(config, FileConfigEntities.newDescriptor(ResourceTypes.APPLICATION, "a1")));
        assertSame(config.getApplications().get("a1"), application);
        assertEquals("m1", application.getDependencies().get(0));
        assertSame(config.getToolsets().get("t1"),
                assertInstanceOf(ToolSet.class, FileConfigEntities.findDeployment(config, FileConfigEntities.newDescriptor(ResourceTypes.TOOL_SET, "t1"))));
        assertEquals("toolset", DependencyTypePolicy.resolveTypeName(ResourceTypes.TOOL_SET));
    }

    @Test
    public void testDeploymentOfIsNullForWhatTheFileConfigurationDoesNotHold() {
        assertNull(FileConfigEntities.findDeployment(config, FileConfigEntities.newDescriptor(ResourceTypes.MODEL, "no-such")));
        assertNull(FileConfigEntities.findDeployment(config, FileConfigEntities.newDescriptor(ResourceTypes.APPLICATION, "m1")), "a name of another kind");
        assertNull(FileConfigEntities.findDeployment(config, FileConfigEntities.newDescriptor(ResourceTypes.PROMPT, "p")), "a type without a file form");
        assertNull(FileConfigEntities.findDeployment(config, FileConfigEntities.newDescriptor(ResourceTypes.APP_TYPE_SCHEMA, "s")),
                "a schema is not a deployment");
        assertNull(FileConfigEntities.findDeployment(null, FileConfigEntities.newDescriptor(ResourceTypes.MODEL, "m1")), "no file configuration");
    }

    @Test
    public void testSchemaOfTheFileConfigurationIsNamedByTheDigestOfItsId() {
        ResourceDescriptor descriptor = FileConfigEntities.newSchemaDescriptor(ResourceTypes.APP_TYPE_SCHEMA, SCHEMA_ID);

        assertEquals(FileConfigEntities.computeSchemaDigest(SCHEMA_ID), descriptor.getName());
        assertEquals("application-type-schema:file:" + FileConfigEntities.computeSchemaDigest(SCHEMA_ID), DependencyResourceNames.buildKey(descriptor));
        assertFalse(DependencyResourceNames.buildKey(descriptor).contains("dial.example"), "the key holds no id of a schema");
    }

    @Test
    public void testSchemaDigestIsTheFirst32HexCharactersOfTheDigestOfTheId() {
        String id = "https://dial.example/schemas/a%20b?x=1#y";
        String digest = FileConfigEntities.computeSchemaDigest(id);

        assertTrue(digest.matches("[0-9a-f]{32}"), digest);
        assertEquals(digest, FileConfigEntities.computeSchemaDigest(id), "the digest follows the $id");
        assertNotEquals(digest, FileConfigEntities.computeSchemaDigest(id + "2"));
        assertEquals(digest, FileConfigEntities.newSchemaDescriptor(ResourceTypes.APP_TYPE_SCHEMA, id).getName());
    }

    @Test
    public void testOnlyTheFileLocationMakesFileEntity() {
        assertTrue(FileConfigEntities.isFile(FileConfigEntities.newDescriptor(ResourceTypes.MODEL, "m1")));
        assertFalse(FileConfigEntities.isFile(descriptor(ResourceTypes.MODEL, "platform", "platform/", "m1")));
    }

    private static ResourceDescriptor descriptor(ResourceTypes type, String bucket, String location, String path) {
        return ResourceDescriptorFactory.fromDecoded(type, bucket, location, path);
    }

    private static ResourceDescriptor publicDescriptor(ResourceTypes type, String path) {
        return descriptor(type, "public", "public/", path);
    }
}

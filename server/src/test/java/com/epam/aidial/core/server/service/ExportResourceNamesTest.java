package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.service.DiscoveredEdge.EdgeStatus;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExportResourceNamesTest {

    private static final List<ResourceTypes> EXPORTED_TYPES = List.of(ResourceTypes.APPLICATION, ResourceTypes.TOOL_SET,
            ResourceTypes.MODEL, ResourceTypes.INTERCEPTOR);

    @Test
    public void testOtherLocationsAreReportedWithoutTheirBucketLocation() {
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, "bucketToken", "Users/someone/", "a.txt");

        assertEquals("files:other:a.txt", ExportResourceNames.buildKey(descriptor), "a type the export does not write keeps its URL segment");
        assertFalse(ExportResourceNames.buildKey(descriptor).contains("Users"), "the location of a private bucket is not disclosed");
    }

    @Test
    public void testKeyOfPlatformPublicAndFileOrigin() {
        assertEquals("application:platform:app", ExportResourceNames.buildKey(descriptor(ResourceTypes.APPLICATION, "platform", "platform/", "app")));
        assertEquals("model:file:m", ExportResourceNames.buildKey(ExportFileEntities.newDescriptor(ResourceTypes.MODEL, "m")));
        assertEquals("application:public:folder/name__1.0.0", ExportResourceNames.buildKey(publicDescriptor(ResourceTypes.APPLICATION, "folder/name__1.0.0")));
    }

    @Test
    public void testFragmentNameKeepsTheFolderVersionAndDotsOfThePath() {
        assertEquals(".dial-export/config/application/app.json",
                ExportResourceNames.buildFragmentName(descriptor(ResourceTypes.APPLICATION, "platform", "platform/", "app")));
        assertEquals(".dial-export/config/application/folder/name__1.0.0.json",
                ExportResourceNames.buildFragmentName(publicDescriptor(ResourceTypes.APPLICATION, "folder/name__1.0.0")));

        for (ResourceTypes type : EXPORTED_TYPES) {
            String segment = ExportResourceNames.resolveTypeName(type);
            assertEquals(".dial-export/config/" + segment + "/a/b/file.v2.json.json",
                    ExportResourceNames.buildFragmentName(publicDescriptor(type, "a/b/file.v2.json")));
            assertEquals(".dial-export/config/" + segment + "/a/..b/c..d/.hidden.json",
                    ExportResourceNames.buildFragmentName(publicDescriptor(type, "a/..b/c..d/.hidden")));
            assertEquals(".dial-export/config/" + segment + "/a/b...json", ExportResourceNames.buildFragmentName(publicDescriptor(type, "a/b..")));
        }
    }

    @Test
    public void testUnsafeCharactersAndNonAsciiBytesOfPathAreEscapedWithoutAmbiguity() {
        ResourceDescriptor colon = publicDescriptor(ResourceTypes.APPLICATION, "dir/a:b%c d.json");
        ResourceDescriptor tilde = publicDescriptor(ResourceTypes.APPLICATION, "a~b");
        ResourceDescriptor escapedTilde = publicDescriptor(ResourceTypes.APPLICATION, "a~7Eb");
        ResourceDescriptor nonAscii = publicDescriptor(ResourceTypes.APPLICATION, "é");

        assertEquals(".dial-export/config/application/dir/a~3Ab~25c~20d.json.json", ExportResourceNames.buildFragmentName(colon));
        assertEquals("application:public:dir/a~3Ab~25c~20d.json", ExportResourceNames.buildKey(colon), "a colon of a path never reaches the key");
        assertEquals(".dial-export/config/application/a~7Eb.json", ExportResourceNames.buildFragmentName(tilde));
        assertNotEquals(ExportResourceNames.buildFragmentName(tilde), ExportResourceNames.buildFragmentName(escapedTilde), "the escape is injective");
        assertEquals(".dial-export/config/application/~C3~A9.json", ExportResourceNames.buildFragmentName(nonAscii));
    }

    @Test
    public void testFragmentNameOfSchemaHasNoColonOrPercent() {
        ResourceDescriptor schema = ExportFileEntities.newSchemaDescriptor(ResourceTypes.APP_TYPE_SCHEMA, "https://dial.example/schemas/a%20b?x=1#y");
        String fragmentName = ExportResourceNames.buildFragmentName(schema);

        assertFalse(fragmentName.contains(":") || fragmentName.contains("%"), fragmentName);
    }

    @Test
    public void testWireNameIsLowerCaseWithHyphens() {
        assertEquals("unresolved-short-name", ExportResourceNames.toWireName(EdgeStatus.UNRESOLVED_SHORT_NAME));
    }

    @Test
    public void testPathWithDotSegmentsOrEmptySegmentsIsRefused() {
        for (String path : List.of(".", "..", "../b", "a/..", "a/../b", "./a", "a/./b", "a/.", "a/b/../../c", "a//b", "/a")) {
            ResourceDescriptor descriptor = publicDescriptor(ResourceTypes.APPLICATION, path);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> ExportResourceNames.requireSafePath(descriptor), path);
            // the paths of this test hold only bytes that the key keeps as they are
            assertTrue(failure.getMessage().endsWith("(resource=application:public:" + path + ")"), failure.getMessage());
            assertEquals("application:public:" + path, ExportResourceNames.buildKey(descriptor), "the key is built for the message of a refusal");
            assertThrows(IllegalStateException.class, () -> ExportResourceNames.requireSafeRelativePath(path, "skills:public:s"), path);
        }
    }

    @Test
    public void testSafePathIsAccepted() {
        ExportResourceNames.requireSafePath(publicDescriptor(ResourceTypes.APPLICATION, "folder/a..b/.hidden"));
        ExportResourceNames.requireSafePath(ExportFileEntities.newDescriptor(ResourceTypes.MODEL, "m"));
        ExportResourceNames.requireSafeRelativePath("docs/a..b/.hidden", "skills:public:s");
    }

    private static ResourceDescriptor descriptor(ResourceTypes type, String bucket, String location, String path) {
        return ResourceDescriptorFactory.fromDecoded(type, bucket, location, path);
    }

    private static ResourceDescriptor publicDescriptor(ResourceTypes type, String path) {
        return descriptor(type, "public", "public/", path);
    }
}

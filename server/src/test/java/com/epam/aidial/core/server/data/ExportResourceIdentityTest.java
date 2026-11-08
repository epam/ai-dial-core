package com.epam.aidial.core.server.data;

import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ExportResourceIdentityTest {

    @Test
    public void testOtherLocationsAreReportedWithoutTheirBucketLocation() {
        ExportResourceIdentity identity = ExportResourceIdentity.ofDescriptor(
                ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, "bucketToken", "Users/someone/", "a.txt"), null);

        assertEquals("other", identity.origin());
        assertNull(identity.bucketLocation(), "the location of a private bucket is not disclosed");
        assertEquals("files", identity.type(), "a type the export does not write keeps its URL segment");
    }

    @Test
    public void testFragmentNameKeepsTheFolderVersionAndDotsOfThePath() {
        ExportResourceIdentity platform = ExportResourceIdentity.ofDescriptor(
                ResourceDescriptorFactory.fromDecoded(ResourceTypes.APPLICATION, "platform", "platform/", "app"), null);
        assertEquals(new ExportResourceIdentity("application", "platform", "platform/", "app", null), platform);
        assertEquals(".dial-export/config/application/app.json", platform.fragmentName());

        ExportResourceIdentity versioned = publicIdentity(ResourceTypes.APPLICATION, "folder/name__1.0.0");
        assertEquals("public", versioned.origin());
        assertEquals("public/", versioned.bucketLocation());
        assertEquals("folder/name__1.0.0", versioned.path());
        assertEquals(".dial-export/config/application/folder/name__1.0.0.json", versioned.fragmentName());

        for (ResourceTypes type : EXPORTED_TYPES) {
            String segment = ExportResourceIdentity.typeName(type);
            assertEquals(".dial-export/config/" + segment + "/a/b/file.v2.json.json", publicIdentity(type, "a/b/file.v2.json").fragmentName());
            assertEquals(".dial-export/config/" + segment + "/a/..b/c..d/.hidden.json", publicIdentity(type, "a/..b/c..d/.hidden").fragmentName());
            assertEquals(".dial-export/config/" + segment + "/a/b...json", publicIdentity(type, "a/b..").fragmentName());
        }
    }

    @Test
    public void testUnsafeCharactersAndNonAsciiBytesOfPathAreEscapedWithoutAmbiguity() {
        ExportResourceIdentity colon = new ExportResourceIdentity("application", "public", "public/", "dir/a:b%c d.json", null);
        ExportResourceIdentity tilde = new ExportResourceIdentity("application", "public", "public/", "a~b", null);
        ExportResourceIdentity escapedTilde = new ExportResourceIdentity("application", "public", "public/", "a~7Eb", null);
        ExportResourceIdentity nonAscii = new ExportResourceIdentity("application", "public", "public/", "é", null);

        assertEquals(".dial-export/config/application/dir/a~3Ab~25c~20d.json.json", colon.fragmentName());
        assertEquals(".dial-export/config/application/a~7Eb.json", tilde.fragmentName());
        assertNotEquals(tilde.fragmentName(), escapedTilde.fragmentName(), "the escape is injective");
        assertEquals(".dial-export/config/application/~C3~A9.json", nonAscii.fragmentName());
    }

    @Test
    public void testSchemaEntryNameIsDigestOfItsIdWithNoColonOrPercent() {
        String id = "https://dial.example/schemas/a%20b?x=1#y";
        ExportResourceIdentity one = new ExportResourceIdentity("application-type-schema", "platform", "platform/", "blob-one", id);
        ExportResourceIdentity same = new ExportResourceIdentity("application-type-schema", "platform", "platform/", "another-blob", id);
        ExportResourceIdentity other = new ExportResourceIdentity("application-type-schema", "platform", "platform/", "blob-one", id + "2");

        assertFalse(one.fragmentName().contains(":") || one.fragmentName().contains("%"), one.fragmentName());
        assertEquals(one.fragmentName(), same.fragmentName(), "the name follows the $id, not the blob name");
        assertNotEquals(one.fragmentName(), other.fragmentName());
    }

    @Test
    public void testSourcePointerIsSetOnlyForFileEntitiesAndNeverHoldsTheSchemaId() {
        assertEquals("/models/m", ExportResourceIdentity.ofFileEntity(ResourceTypes.MODEL, "m", null).sourcePointer());
        assertEquals("/applications/a", ExportResourceIdentity.ofFileEntity(ResourceTypes.APPLICATION, "a", null).sourcePointer());
        assertEquals("/toolsets/t", ExportResourceIdentity.ofFileEntity(ResourceTypes.TOOL_SET, "t", null).sourcePointer());
        assertEquals("/interceptors/i", ExportResourceIdentity.ofFileEntity(ResourceTypes.INTERCEPTOR, "i", null).sourcePointer());
        String digest = ExportResourceIdentity.schemaDigest("https://dial.example/s");
        assertEquals("/applicationTypeSchemas/" + digest,
                ExportResourceIdentity.ofFileEntity(ResourceTypes.APP_TYPE_SCHEMA, digest, "https://dial.example/s").sourcePointer());
        assertEquals("/catalogSchemas/" + digest,
                ExportResourceIdentity.ofFileEntity(ResourceTypes.CATALOG_SCHEMA, digest, "https://dial.example/s").sourcePointer());
        assertEquals("/models/a~1b~0c", ExportResourceIdentity.ofFileEntity(ResourceTypes.MODEL, "a/b~c", null).sourcePointer(),
                "RFC 6901 escaping");
        assertNull(ExportResourceIdentity.ofDescriptor(
                ResourceDescriptorFactory.fromDecoded(ResourceTypes.MODEL, "platform", "platform/", "m"), null).sourcePointer());
    }

    private static final List<ResourceTypes> EXPORTED_TYPES = List.of(ResourceTypes.APPLICATION, ResourceTypes.TOOL_SET,
            ResourceTypes.MODEL, ResourceTypes.INTERCEPTOR);

    private static ExportResourceIdentity publicIdentity(ResourceTypes type, String path) {
        return ExportResourceIdentity.ofDescriptor(ResourceDescriptorFactory.fromDecoded(type, "public", "public/", path), null);
    }

    @Test
    public void testPathWithDotSegmentsOrEmptySegmentsHasNoEntryName() {
        for (String path : List.of(".", "..", "../b", "a/..", "a/../b", "./a", "a/./b", "a/.", "a/b/../../c", "a//b", "/a")) {
            ExportResourceIdentity identity = publicIdentity(ResourceTypes.APPLICATION, path);
            IllegalStateException failure = assertThrows(IllegalStateException.class, identity::fragmentName, path);
            assertFalse(failure.getMessage().contains(path), "the message echoes the path");
        }
    }

}

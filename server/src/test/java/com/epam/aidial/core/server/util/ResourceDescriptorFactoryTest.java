package com.epam.aidial.core.server.util;

import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResourceDescriptorFactoryTest {

    @Test
    public void testHomeFolderDescription() {
        ResourceDescriptor resource = ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "aes-bucket-name", "buckets/location/", "/");
        assertNull(resource.getName());
        assertEquals("aes-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceTypes.FILE, resource.getType());
        assertEquals("files/aes-bucket-name/", resource.getUrl());
        assertEquals("buckets/location/files/", resource.getAbsoluteFilePath());
        assertTrue(resource.isFolder());
        assertNull(resource.getParentPath());
        assertTrue(resource.getParentFolders().isEmpty());
    }

    @Test
    public void testUserFolderDescription() {
        ResourceDescriptor resource = ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "test-bucket-name", "buckets/location/", "folder%201/");
        assertEquals("folder 1", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceTypes.FILE, resource.getType());
        assertEquals("files/test-bucket-name/folder%201/", resource.getUrl());
        assertEquals("buckets/location/files/folder 1/", resource.getAbsoluteFilePath());
        assertTrue(resource.isFolder());
        assertNull(resource.getParentPath());
        assertTrue(resource.getParentFolders().isEmpty());
    }

    @Test
    public void testUserFolderDescription2() {
        ResourceDescriptor resource = ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "test-bucket-name", "buckets/location/", "folder1/folder2/");
        assertEquals("folder2", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceTypes.FILE, resource.getType());
        assertEquals("files/test-bucket-name/folder1/folder2/", resource.getUrl());
        assertEquals("buckets/location/files/folder1/folder2/", resource.getAbsoluteFilePath());
        assertTrue(resource.isFolder());
        assertEquals("folder1", resource.getParentPath());
        assertIterableEquals(List.of("folder1"), resource.getParentFolders());
    }

    @Test
    public void testUserFolderDescription3() {
        ResourceDescriptor resource = ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "test-bucket-name", "buckets/location/", "folder1/folder2/folder3/");
        assertEquals("folder3", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceTypes.FILE, resource.getType());
        assertEquals("files/test-bucket-name/folder1/folder2/folder3/", resource.getUrl());
        assertEquals("buckets/location/files/folder1/folder2/folder3/", resource.getAbsoluteFilePath());
        assertTrue(resource.isFolder());
        assertEquals("folder1/folder2", resource.getParentPath());
        assertIterableEquals(List.of("folder1", "folder2"), resource.getParentFolders());
    }

    @Test
    public void testFileDescription1() {
        ResourceDescriptor resource = ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "test-bucket-name", "buckets/location/", "file.txt");
        assertEquals("file.txt", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceTypes.FILE, resource.getType());
        assertEquals("files/test-bucket-name/file.txt", resource.getUrl());
        assertEquals("buckets/location/files/file.txt", resource.getAbsoluteFilePath());
        assertFalse(resource.isFolder());
        assertNull(resource.getParentPath());
        assertTrue(resource.getParentFolders().isEmpty());
    }

    @Test
    public void testFileDescription2() {
        ResourceDescriptor resource = ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "test-bucket-name", "buckets/location/", "folder1/file.txt");
        assertEquals("file.txt", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceTypes.FILE, resource.getType());
        assertEquals("files/test-bucket-name/folder1/file.txt", resource.getUrl());
        assertEquals("buckets/location/files/folder1/file.txt", resource.getAbsoluteFilePath());
        assertFalse(resource.isFolder());
        assertEquals("folder1", resource.getParentPath());
        assertIterableEquals(List.of("folder1"), resource.getParentFolders());
    }

    @Test
    public void testFileDescription3() {
        ResourceDescriptor resource = ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "test-bucket-name", "buckets/location/", "folder1/folder2/file.txt");
        assertEquals("file.txt", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceTypes.FILE, resource.getType());
        assertEquals("files/test-bucket-name/folder1/folder2/file.txt", resource.getUrl());
        assertEquals("buckets/location/files/folder1/folder2/file.txt", resource.getAbsoluteFilePath());
        assertFalse(resource.isFolder());
        assertEquals("folder1/folder2", resource.getParentPath());
        assertIterableEquals(List.of("folder1", "folder2"), resource.getParentFolders());
    }

    @Test
    public void testInvalidBucketLocation() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket-name", "buckets/location", "file.txt"));
    }

    @Test
    public void testEmptyRelativePath() {
        assertEquals(
                ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket", "location/", "/"),
                ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket", "location/", "")
        );
        assertEquals(
                ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket", "location/", "/"),
                ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket", "location/", null)
        );
        assertEquals(
                ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket", "location/", "/"),
                ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket", "location/", "   ")
        );
    }

    @Test
    public void testResourceWithInvalidFilename() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket", "location/", "%2F"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket", "location/", "%7D.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket", "location/", "folde%2F/"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket", "location/", "folder1/file%2F.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket", "location/", "folder1/file%7B.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket", "location/", "folder1/file%7D.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket", "location/", "folder1/file%00"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket", "location/", "%1Ffolder1/file"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket", "location/", "fol%0Fder1"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket", "location/", "//file.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, "bucket", "location/", "folder1%22"));
    }

    @Test
    public void testValidPublicLinks() {
        assertEquals(
                ResourceDescriptorFactory.fromPublicUrl("publications/public/"),
                ResourceDescriptorFactory.fromEncoded(ResourceTypes.PUBLICATION, "public", "public/", "")
        );

        assertEquals(
                ResourceDescriptorFactory.fromPublicUrl("publications/public/file"),
                ResourceDescriptorFactory.fromEncoded(ResourceTypes.PUBLICATION, "public", "public/", "file")
        );

        assertEquals(
                ResourceDescriptorFactory.fromPublicUrl("publications/public/folder/"),
                ResourceDescriptorFactory.fromEncoded(ResourceTypes.PUBLICATION, "public", "public/", "folder/")
        );

        assertEquals(
                ResourceDescriptorFactory.fromPublicUrl("publications/public/%30").getName(),
                "0"
        );
    }

    @Test
    public void testInvalidPublicLinks() {
        assertThrows(IllegalArgumentException.class, () -> ResourceDescriptorFactory.fromPublicUrl("/publications/public/"));
        assertThrows(IllegalArgumentException.class, () -> ResourceDescriptorFactory.fromPublicUrl("publications/public"));
        assertThrows(IllegalArgumentException.class, () -> ResourceDescriptorFactory.fromPublicUrl("publications/public"));
        assertThrows(IllegalArgumentException.class, () -> ResourceDescriptorFactory.fromPublicUrl("publications/private/"));
    }

    @Test
    public void testFromAnyUrl_File() {
        JsonObject settings = new JsonObject();
        settings.put("secret", "secret");
        settings.put("key", "key");
        EncryptionService service = new EncryptionService(settings);
        String bucketLocation = "Users/user1/";
        String bucketName = service.encrypt(bucketLocation);
        ResourceDescriptor resourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(ResourceTypes.FILE.group() + "/" + bucketName + "/my/folder/file.txt", service);
        assertEquals(bucketName, resourceDescriptor.getBucketName());
        assertEquals(bucketLocation, resourceDescriptor.getBucketLocation());
        assertEquals("file.txt", resourceDescriptor.getName());
        assertEquals(List.of("my", "folder"), resourceDescriptor.getParentFolders());
        assertEquals(ResourceTypes.FILE, resourceDescriptor.getType());
        assertFalse(resourceDescriptor.isFolder());

    }

    @Test
    public void testFromAnyUrl_Folder() {
        JsonObject settings = new JsonObject();
        settings.put("secret", "secret");
        settings.put("key", "key");
        EncryptionService service = new EncryptionService(settings);
        String bucketLocation = "Users/user1/";
        String bucketName = service.encrypt(bucketLocation);
        ResourceDescriptor resourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(ResourceTypes.FILE.group() + "/" + bucketName + "/my/folder/", service);
        assertEquals(bucketName, resourceDescriptor.getBucketName());
        assertEquals(bucketLocation, resourceDescriptor.getBucketLocation());
        assertEquals("folder", resourceDescriptor.getName());
        assertEquals(List.of("my"), resourceDescriptor.getParentFolders());
        assertEquals(ResourceTypes.FILE, resourceDescriptor.getType());
        assertTrue(resourceDescriptor.isFolder());

    }

    @Test
    public void testPlatformConstants() {
        assertEquals("platform", ResourceDescriptor.PLATFORM_BUCKET);
        assertEquals("platform/", ResourceDescriptor.PLATFORM_LOCATION);
    }

    @Test
    public void testFromAnyUrl_PlatformBucket() {
        EncryptionService service = new EncryptionService(new JsonObject().put("secret", "secret").put("key", "key"));
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl("models/platform/gpt-4", service);

        assertEquals(ResourceTypes.MODEL, descriptor.getType());
        assertEquals(ResourceDescriptor.PLATFORM_BUCKET, descriptor.getBucketName());
        assertEquals(ResourceDescriptor.PLATFORM_LOCATION, descriptor.getBucketLocation());
        assertEquals("gpt-4", descriptor.getName());
        assertFalse(descriptor.isPublic());
    }

    @Test
    public void testResourceTypesOfNewGroups() {
        assertEquals(ResourceTypes.MODEL, ResourceTypes.of("models"));
        assertEquals(ResourceTypes.APP_TYPE_SCHEMA, ResourceTypes.of("app_type_schemas"));
        assertEquals(ResourceTypes.INTERCEPTOR, ResourceTypes.of("interceptors"));
        assertEquals(ResourceTypes.ROLE, ResourceTypes.of("roles"));
        assertEquals(ResourceTypes.PROJECT_KEY, ResourceTypes.of("project_keys"));
        assertEquals(ResourceTypes.ROUTE, ResourceTypes.of("routes"));
        assertEquals(ResourceTypes.GLOBAL_SETTINGS, ResourceTypes.of("settings"));
    }

    @Test
    public void testResourceTypesOfUrlSegmentAliases() {
        assertEquals(ResourceTypes.APP_TYPE_SCHEMA, ResourceTypes.of("schemas"));
        assertEquals(ResourceTypes.PROJECT_KEY, ResourceTypes.of("keys"));
    }

    @Test
    public void testUrlSegmentRoundTrip_Schemas() {
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl("schemas/platform/foo", null);
        assertEquals("schemas/platform/foo", descriptor.getUrl());
        assertEquals("schemas/platform/foo", descriptor.getDecodedUrl());
        assertEquals("platform/app_type_schemas/foo", descriptor.getAbsoluteFilePath());
    }

    @Test
    public void testUrlSegmentRoundTrip_Keys() {
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl("keys/platform/proxyKey1", null);
        assertEquals("keys/platform/proxyKey1", descriptor.getUrl());
        assertEquals("keys/platform/proxyKey1", descriptor.getDecodedUrl());
        assertEquals("platform/project_keys/proxyKey1", descriptor.getAbsoluteFilePath());
    }

    @Test
    public void testUrlSegmentDefault_NonAliasedType() {
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl("models/platform/gpt-4", null);
        assertEquals("models/platform/gpt-4", descriptor.getUrl());
        assertEquals("platform/models/gpt-4", descriptor.getAbsoluteFilePath());
    }

    @Test
    public void testFromAnyUrl_RootFolder() {
        JsonObject settings = new JsonObject();
        settings.put("secret", "secret");
        settings.put("key", "key");
        EncryptionService service = new EncryptionService(settings);
        String bucketLocation = "Users/user1/";
        String bucketName = service.encrypt(bucketLocation);
        ResourceDescriptor resourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(ResourceTypes.FILE.group() + "/" + bucketName + "/", service);
        assertEquals(bucketName, resourceDescriptor.getBucketName());
        assertEquals(bucketLocation, resourceDescriptor.getBucketLocation());
        assertNull(resourceDescriptor.getName());
        assertEquals(List.of(), resourceDescriptor.getParentFolders());
        assertEquals(ResourceTypes.FILE, resourceDescriptor.getType());
        assertTrue(resourceDescriptor.isFolder());

    }

    /**
     * A deployment name is plain configuration text, not a url. {@code [} and {@code ]} are illegal in a URI
     * path, so validating one as a URI is what made {@code GET /v1/user/usage} fail for a model named
     * {@code anthropic.claude-opus-4-8[1m]}.
     */
    @Test
    public void testFromEntityPath_NameThatIsNotUriSafe() {
        ResourceDescriptor resource = ResourceDescriptorFactory.fromEntityPath(
                ResourceTypes.LIMIT, "buckets/location/", "buckets/location/", "anthropic.claude-opus-4-8[1m]/tokens");
        assertEquals("tokens", resource.getName());
        assertEquals("anthropic.claude-opus-4-8[1m]", resource.getParentPath());
        assertEquals("buckets/location/limits/anthropic.claude-opus-4-8[1m]/tokens", resource.getAbsoluteFilePath());
        assertFalse(resource.isFolder());

        // what the incident hit: the same path through the strict factory fails its URI check. Note the name
        // passes isValidFilename - brackets are not in INVALID_FILE_NAME_CHARS - so the URI check is the whole
        // of the defect, and relaxing anything else would be gratuitous
        assertThrows(RuntimeException.class, () -> ResourceDescriptorFactory.fromEncoded(
                ResourceTypes.LIMIT, "buckets/location/", "buckets/location/", "anthropic.claude-opus-4-8[1m]/tokens"));
    }

    /**
     * Why the decode step is kept: a custom application reports an already encoded resource url as its name,
     * so dropping the decode would move every stored record of such a deployment to a new key. A spot check of
     * representative shapes, not a proof - the guarantee comes from both factories sharing the decode call, and
     * differing only in whether it may throw.
     *
     * <p>The non-ASCII case pins that the two agree; it says nothing about which bytes are correct, since both
     * sides route through {@code Charset.defaultCharset()}.
     */
    @Test
    public void testFromEntityPath_KeepsTheKeyFromEncodedBuilds() {
        for (String path : List.of("model/tokens", "applications/buck/my%20app/tokens", "модель/tokens", "costs")) {
            assertEquals(
                    ResourceDescriptorFactory.fromEncoded(ResourceTypes.LIMIT, "b", "location/", path).getAbsoluteFilePath(),
                    ResourceDescriptorFactory.fromEntityPath(ResourceTypes.LIMIT, "b", "location/", path).getAbsoluteFilePath(),
                    path);
        }
    }

    /**
     * Only the URI check is relaxed. The file name charset is not: a brace lets the caller pick the Redis
     * Cluster hash slot, and a double quote is rejected on purpose (see commit bc971243) because it cannot be
     * stored by every provider.
     */
    @Test
    public void testFromEntityPath_KeepsTheFileNameCharset() {
        for (String path : List.of("gpt{4}/tokens", "gpt}4/tokens", "gpt\"4/tokens", "%7Bgpt%7D/tokens")) {
            assertThrows(IllegalArgumentException.class, () -> ResourceDescriptorFactory.fromEntityPath(
                    ResourceTypes.LIMIT, "b", "location/", path), path);
        }
    }

    /**
     * A name that is not valid percent encoding is kept verbatim instead of failing to decode.
     */
    @Test
    public void testFromEntityPath_KeepsBrokenPercentEncodingVerbatim() {
        assertEquals("location/limits/100% off/tokens", ResourceDescriptorFactory.fromEntityPath(
                ResourceTypes.LIMIT, "b", "location/", "100% off/tokens").getAbsoluteFilePath());
    }

    @Test
    public void testFromEntityPath_StillRejectsUnstorableElements() {
        // an encoded separator decodes after the split, so it would silently add a path level
        assertThrows(IllegalArgumentException.class, () -> ResourceDescriptorFactory.fromEntityPath(
                ResourceTypes.LIMIT, "b", "location/", "a%2Fb/tokens"));
        assertThrows(IllegalArgumentException.class, () -> ResourceDescriptorFactory.fromEntityPath(
                ResourceTypes.LIMIT, "b", "location/", "a%00b/tokens"));
        assertThrows(IllegalArgumentException.class, () -> ResourceDescriptorFactory.fromEntityPath(
                ResourceTypes.LIMIT, "b", "location/", "//tokens"));
        assertThrows(IllegalArgumentException.class, () -> ResourceDescriptorFactory.fromEntityPath(
                ResourceTypes.LIMIT, "b", "location/", "x".repeat(1000) + "/tokens"));
    }
}

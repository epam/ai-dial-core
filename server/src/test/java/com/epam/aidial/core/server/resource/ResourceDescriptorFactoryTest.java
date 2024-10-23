package com.epam.aidial.core.server.resource;

import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.security.EncryptionService;
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
        assertEquals(bucketName, resourceDescriptor.bucketName);
        assertEquals(bucketLocation, resourceDescriptor.bucketLocation);
        assertEquals("file.txt", resourceDescriptor.name);
        assertEquals(List.of("my", "folder"), resourceDescriptor.parentFolders);
        assertEquals(ResourceTypes.FILE, resourceDescriptor.type);
        assertFalse(resourceDescriptor.isFolder);

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
        assertEquals(bucketName, resourceDescriptor.bucketName);
        assertEquals(bucketLocation, resourceDescriptor.bucketLocation);
        assertEquals("folder", resourceDescriptor.name);
        assertEquals(List.of("my"), resourceDescriptor.parentFolders);
        assertEquals(ResourceTypes.FILE, resourceDescriptor.type);
        assertTrue(resourceDescriptor.isFolder);

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
        assertEquals(bucketName, resourceDescriptor.bucketName);
        assertEquals(bucketLocation, resourceDescriptor.bucketLocation);
        assertNull(resourceDescriptor.name);
        assertEquals(List.of(), resourceDescriptor.parentFolders);
        assertEquals(ResourceTypes.FILE, resourceDescriptor.type);
        assertTrue(resourceDescriptor.isFolder);

    }
}

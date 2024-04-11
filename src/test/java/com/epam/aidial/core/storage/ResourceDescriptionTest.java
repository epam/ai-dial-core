package com.epam.aidial.core.storage;

import com.epam.aidial.core.data.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResourceDescriptionTest {

    @Test
    public void testHomeFolderDescription() {
        ResourceDescription resource = ResourceDescription.fromEncoded(ResourceType.FILE, "aes-bucket-name", "buckets/location/", "/");
        assertNull(resource.getName());
        assertEquals("aes-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceType.FILE, resource.getType());
        assertEquals("files/aes-bucket-name/", resource.getUrl());
        assertEquals("buckets/location/files/", resource.getAbsoluteFilePath());
        assertEquals("/", resource.getOriginalPath());
        assertTrue(resource.isFolder());
        assertNull(resource.getParentPath());
        assertTrue(resource.getParentFolders().isEmpty());
    }

    @Test
    public void testUserFolderDescription() {
        ResourceDescription resource = ResourceDescription.fromEncoded(ResourceType.FILE, "test-bucket-name", "buckets/location/", "folder%201/");
        assertEquals("folder 1", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceType.FILE, resource.getType());
        assertEquals("files/test-bucket-name/folder%201/", resource.getUrl());
        assertEquals("buckets/location/files/folder 1/", resource.getAbsoluteFilePath());
        assertEquals("folder%201/", resource.getOriginalPath());
        assertTrue(resource.isFolder());
        assertNull(resource.getParentPath());
        assertTrue(resource.getParentFolders().isEmpty());
    }

    @Test
    public void testUserFolderDescription2() {
        ResourceDescription resource = ResourceDescription.fromEncoded(ResourceType.FILE, "test-bucket-name", "buckets/location/", "folder1/folder2/");
        assertEquals("folder2", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceType.FILE, resource.getType());
        assertEquals("files/test-bucket-name/folder1/folder2/", resource.getUrl());
        assertEquals("buckets/location/files/folder1/folder2/", resource.getAbsoluteFilePath());
        assertEquals("folder1/folder2/", resource.getOriginalPath());
        assertTrue(resource.isFolder());
        assertEquals("folder1", resource.getParentPath());
        assertIterableEquals(List.of("folder1"), resource.getParentFolders());
    }

    @Test
    public void testUserFolderDescription3() {
        ResourceDescription resource = ResourceDescription.fromEncoded(ResourceType.FILE, "test-bucket-name", "buckets/location/", "folder1/folder2/folder3/");
        assertEquals("folder3", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceType.FILE, resource.getType());
        assertEquals("files/test-bucket-name/folder1/folder2/folder3/", resource.getUrl());
        assertEquals("buckets/location/files/folder1/folder2/folder3/", resource.getAbsoluteFilePath());
        assertEquals("folder1/folder2/folder3/", resource.getOriginalPath());
        assertTrue(resource.isFolder());
        assertEquals("folder1/folder2", resource.getParentPath());
        assertIterableEquals(List.of("folder1", "folder2"), resource.getParentFolders());
    }

    @Test
    public void testFileDescription1() {
        ResourceDescription resource = ResourceDescription.fromEncoded(ResourceType.FILE, "test-bucket-name", "buckets/location/", "file.txt");
        assertEquals("file.txt", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceType.FILE, resource.getType());
        assertEquals("files/test-bucket-name/file.txt", resource.getUrl());
        assertEquals("buckets/location/files/file.txt", resource.getAbsoluteFilePath());
        assertEquals("file.txt", resource.getOriginalPath());
        assertFalse(resource.isFolder());
        assertNull(resource.getParentPath());
        assertTrue(resource.getParentFolders().isEmpty());
    }

    @Test
    public void testFileDescription2() {
        ResourceDescription resource = ResourceDescription.fromEncoded(ResourceType.FILE, "test-bucket-name", "buckets/location/", "folder1/file.txt");
        assertEquals("file.txt", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceType.FILE, resource.getType());
        assertEquals("files/test-bucket-name/folder1/file.txt", resource.getUrl());
        assertEquals("buckets/location/files/folder1/file.txt", resource.getAbsoluteFilePath());
        assertEquals("folder1/file.txt", resource.getOriginalPath());
        assertFalse(resource.isFolder());
        assertEquals("folder1", resource.getParentPath());
        assertIterableEquals(List.of("folder1"), resource.getParentFolders());
    }

    @Test
    public void testFileDescription3() {
        ResourceDescription resource = ResourceDescription.fromEncoded(ResourceType.FILE, "test-bucket-name", "buckets/location/", "folder1/folder2/file.txt");
        assertEquals("file.txt", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceType.FILE, resource.getType());
        assertEquals("files/test-bucket-name/folder1/folder2/file.txt", resource.getUrl());
        assertEquals("buckets/location/files/folder1/folder2/file.txt", resource.getAbsoluteFilePath());
        assertEquals("folder1/folder2/file.txt", resource.getOriginalPath());
        assertFalse(resource.isFolder());
        assertEquals("folder1/folder2", resource.getParentPath());
        assertIterableEquals(List.of("folder1", "folder2"), resource.getParentFolders());
    }

    @Test
    public void testInvalidBucketLocation() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescription.fromEncoded(ResourceType.FILE, "bucket-name", "buckets/location", "file.txt"));
    }

    @Test
    public void testEmptyRelativePath() {
        assertEquals(
                ResourceDescription.fromEncoded(ResourceType.FILE, "bucket", "location/", "/"),
                ResourceDescription.fromEncoded(ResourceType.FILE, "bucket", "location/", "")
        );
        assertEquals(
                ResourceDescription.fromEncoded(ResourceType.FILE, "bucket", "location/", "/"),
                ResourceDescription.fromEncoded(ResourceType.FILE, "bucket", "location/", null)
        );
        assertEquals(
                ResourceDescription.fromEncoded(ResourceType.FILE, "bucket", "location/", "/"),
                ResourceDescription.fromEncoded(ResourceType.FILE, "bucket", "location/", "   ")
        );
    }

    @Test
    public void testResourceWithInvalidFilename() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescription.fromEncoded(ResourceType.FILE, "bucket", "location/", "%2F"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescription.fromEncoded(ResourceType.FILE, "bucket", "location/", "%7D.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescription.fromEncoded(ResourceType.FILE, "bucket", "location/", "folde%2F/"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescription.fromEncoded(ResourceType.FILE, "bucket", "location/", "folder1/file%2F.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescription.fromEncoded(ResourceType.FILE, "bucket", "location/", "folder1/file%7B.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescription.fromEncoded(ResourceType.FILE, "bucket", "location/", "folder1/file%7D.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescription.fromEncoded(ResourceType.FILE, "bucket", "location/", "folder1/file%00"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescription.fromEncoded(ResourceType.FILE, "bucket", "location/", "%1Ffolder1/file"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescription.fromEncoded(ResourceType.FILE, "bucket", "location/", "fol%0Fder1"));
    }

    @Test
    public void testValidPublicLinks() {
        assertEquals(
                ResourceDescription.fromPublicUrl("publications/public/"),
                ResourceDescription.fromEncoded(ResourceType.PUBLICATION, "public", "public/", "")
        );

        assertEquals(
                ResourceDescription.fromPublicUrl("publications/public/file"),
                ResourceDescription.fromEncoded(ResourceType.PUBLICATION, "public", "public/", "file")
        );

        assertEquals(
                ResourceDescription.fromPublicUrl("publications/public/folder/"),
                ResourceDescription.fromEncoded(ResourceType.PUBLICATION, "public", "public/", "folder/")
        );

        assertEquals(
                ResourceDescription.fromPublicUrl("publications/public/%30").getName(),
                "0"
        );
    }

    @Test
    public void testInvalidPublicLinks() {
        assertThrows(IllegalArgumentException.class, () -> ResourceDescription.fromPublicUrl("/publications/public/"));
        assertThrows(IllegalArgumentException.class, () -> ResourceDescription.fromPublicUrl("publications/public"));
        assertThrows(IllegalArgumentException.class, () -> ResourceDescription.fromPublicUrl("publications/public"));
        assertThrows(IllegalArgumentException.class, () -> ResourceDescription.fromPublicUrl("publications/private/"));
    }
}

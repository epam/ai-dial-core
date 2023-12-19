package com.epam.aidial.core.storage;

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
        ResourceDescription resource = ResourceDescription.from(ResourceType.FILE, "aes-bucket-name", "buckets/location/", "/");
        assertEquals("/", resource.getName());
        assertEquals("aes-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceType.FILE, resource.getType());
        assertEquals("aes-bucket-name/", resource.getUrl());
        assertEquals("buckets/location/files/", resource.getAbsoluteFilePath());
        assertEquals("/", resource.getOriginalPath());
        assertTrue(resource.isFolder());
        assertNull(resource.getParentPath());
        assertNull(resource.getParentFolders());
    }

    @Test
    public void testUserFolderDescription() {
        ResourceDescription resource = ResourceDescription.from(ResourceType.FILE, "test-bucket-name", "buckets/location/", "folder1/");
        assertEquals("folder1", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceType.FILE, resource.getType());
        assertEquals("test-bucket-name/folder1/", resource.getUrl());
        assertEquals("buckets/location/files/folder1/", resource.getAbsoluteFilePath());
        assertEquals("folder1/", resource.getOriginalPath());
        assertTrue(resource.isFolder());
        assertNull(resource.getParentPath());
        assertNull(resource.getParentFolders());
    }

    @Test
    public void testUserFolderDescription2() {
        ResourceDescription resource = ResourceDescription.from(ResourceType.FILE, "test-bucket-name", "buckets/location/", "folder1/folder2/");
        assertEquals("folder2", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceType.FILE, resource.getType());
        assertEquals("test-bucket-name/folder1/folder2/", resource.getUrl());
        assertEquals("buckets/location/files/folder1/folder2/", resource.getAbsoluteFilePath());
        assertEquals("folder1/folder2/", resource.getOriginalPath());
        assertTrue(resource.isFolder());
        assertEquals("folder1", resource.getParentPath());
        assertIterableEquals(List.of("folder1"), resource.getParentFolders());
    }

    @Test
    public void testUserFolderDescription3() {
        ResourceDescription resource = ResourceDescription.from(ResourceType.FILE, "test-bucket-name", "buckets/location/", "folder1/folder2/folder3/");
        assertEquals("folder3", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceType.FILE, resource.getType());
        assertEquals("test-bucket-name/folder1/folder2/folder3/", resource.getUrl());
        assertEquals("buckets/location/files/folder1/folder2/folder3/", resource.getAbsoluteFilePath());
        assertEquals("folder1/folder2/folder3/", resource.getOriginalPath());
        assertTrue(resource.isFolder());
        assertEquals("folder1/folder2", resource.getParentPath());
        assertIterableEquals(List.of("folder1", "folder2"), resource.getParentFolders());
    }

    @Test
    public void testFileDescription1() {
        ResourceDescription resource = ResourceDescription.from(ResourceType.FILE, "test-bucket-name", "buckets/location/", "file.txt");
        assertEquals("file.txt", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceType.FILE, resource.getType());
        assertEquals("test-bucket-name/file.txt", resource.getUrl());
        assertEquals("buckets/location/files/file.txt", resource.getAbsoluteFilePath());
        assertEquals("file.txt", resource.getOriginalPath());
        assertFalse(resource.isFolder());
        assertNull(resource.getParentPath());
        assertNull(resource.getParentFolders());
    }

    @Test
    public void testFileDescription2() {
        ResourceDescription resource = ResourceDescription.from(ResourceType.FILE, "test-bucket-name", "buckets/location/", "folder1/file.txt");
        assertEquals("file.txt", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceType.FILE, resource.getType());
        assertEquals("test-bucket-name/folder1/file.txt", resource.getUrl());
        assertEquals("buckets/location/files/folder1/file.txt", resource.getAbsoluteFilePath());
        assertEquals("folder1/file.txt", resource.getOriginalPath());
        assertFalse(resource.isFolder());
        assertEquals("folder1", resource.getParentPath());
        assertIterableEquals(List.of("folder1"), resource.getParentFolders());
    }

    @Test
    public void testFileDescription3() {
        ResourceDescription resource = ResourceDescription.from(ResourceType.FILE, "test-bucket-name", "buckets/location/", "folder1/folder2/file.txt");
        assertEquals("file.txt", resource.getName());
        assertEquals("test-bucket-name", resource.getBucketName());
        assertEquals("buckets/location/", resource.getBucketLocation());
        assertEquals(ResourceType.FILE, resource.getType());
        assertEquals("test-bucket-name/folder1/folder2/file.txt", resource.getUrl());
        assertEquals("buckets/location/files/folder1/folder2/file.txt", resource.getAbsoluteFilePath());
        assertEquals("folder1/folder2/file.txt", resource.getOriginalPath());
        assertFalse(resource.isFolder());
        assertEquals("folder1/folder2", resource.getParentPath());
        assertIterableEquals(List.of("folder1", "folder2"), resource.getParentFolders());
    }

    @Test
    public void testInvalidBucketLocation() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescription.from(ResourceType.FILE, "bucket-name", "buckets/location", "file.txt"));
    }

    @Test
    public void testEmptyRelativePath() {
        assertEquals(
                ResourceDescription.from(ResourceType.FILE, "bucket", "location/", "/"),
                ResourceDescription.from(ResourceType.FILE, "bucket", "location/", "")
        );
        assertEquals(
                ResourceDescription.from(ResourceType.FILE, "bucket", "location/", "/"),
                ResourceDescription.from(ResourceType.FILE, "bucket", "location/", null)
        );
        assertEquals(
                ResourceDescription.from(ResourceType.FILE, "bucket", "location/", "/"),
                ResourceDescription.from(ResourceType.FILE, "bucket", "location/", "   ")
        );
    }

    @Test
    public void testResourceWithInvalidFilename() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescription.from(ResourceType.FILE, "bucket", "location/", "folde%2F/"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDescription.from(ResourceType.FILE, "bucket", "location/", "folder1/file%2F.txt"));
    }
}

package com.epam.aidial.core.storage;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static com.epam.aidial.core.storage.BlobStorageUtil.normalizePathForQuery;
import static com.epam.aidial.core.storage.BlobStorageUtil.removeLeadingAndTrailingPathSeparators;
import static com.epam.aidial.core.storage.BlobStorageUtil.removeLeadingPathSeparator;
import static com.epam.aidial.core.storage.BlobStorageUtil.removeTrailingPathSeparator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class BlobStorageUtilTest {

    @Test
    public void testNormalizePathForQuery() {
        assertEquals("Users/User/files/", normalizePathForQuery("/Users/User/files"));
        assertEquals("Users/User/files/", normalizePathForQuery("Users/User/files"));
        assertEquals("folder/", normalizePathForQuery("folder"));
        assertEquals("/", normalizePathForQuery("/"));
        assertNull(normalizePathForQuery(""));
    }

    @Test
    public void testRemoveLeadingPathSeparator() {
        assertEquals("Users/User/files/", removeLeadingPathSeparator("/Users/User/files/"));
        assertEquals("Users/User/files", removeLeadingPathSeparator("Users/User/files"));
        assertEquals("folder/", removeLeadingPathSeparator("/folder/"));
        assertEquals("", removeLeadingPathSeparator("/"));
        assertNull(removeLeadingPathSeparator(""));
    }

    @Test
    public void testRemoveTrailingPathSeparator() {
        assertEquals("/Users/User/files", removeTrailingPathSeparator("/Users/User/files/"));
        assertEquals("Users/User/files", removeTrailingPathSeparator("Users/User/files"));
        assertEquals("/folder", removeTrailingPathSeparator("/folder/"));
        assertEquals("", removeTrailingPathSeparator("/"));
        assertNull(removeTrailingPathSeparator(""));
    }

    @Test
    public void testRemoveLeadingAndTrailingPathSeparators() {
        assertEquals("Users/User/files", removeLeadingAndTrailingPathSeparators("/Users/User/files/"));
        assertEquals("Users/User/files", removeLeadingAndTrailingPathSeparators("Users/User/files"));
        assertEquals("folder", removeLeadingAndTrailingPathSeparators("/folder/"));
        assertEquals("", removeLeadingAndTrailingPathSeparators(null));
        assertEquals("", removeLeadingAndTrailingPathSeparators("/"));
        assertEquals("", removeLeadingAndTrailingPathSeparators(""));
    }
}

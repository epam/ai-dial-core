package com.epam.aidial.core.credentials.mapper;

import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialsDescriptorToResourceDescriptorMapperTest {

    private final CredentialsDescriptorToResourceDescriptorMapper mapper = new CredentialsDescriptorToResourceDescriptorMapper();

    @Test
    void testMap_SimpleResourceId() {
        CredentialsDescriptor credentialsDescriptor = new CredentialsDescriptor("folder1/folder2/cred1", "bucketA", "locationA/");
        ResourceDescriptor resourceDescriptor = mapper.map(credentialsDescriptor);

        assertEquals(ResourceTypes.CREDENTIALS, resourceDescriptor.getType());
        assertEquals("cred1", resourceDescriptor.getName());
        assertEquals(List.of("folder1", "folder2"), resourceDescriptor.getParentFolders());
        assertEquals("bucketA", resourceDescriptor.getBucketName());
        assertEquals("locationA/", resourceDescriptor.getBucketLocation());
        assertFalse(resourceDescriptor.isFolder());
    }

    @Test
    void testMap_ResourceIdWithNoFolders() {
        CredentialsDescriptor credentialsDescriptor = new CredentialsDescriptor("cred1", "bucketA", "locationA/");
        ResourceDescriptor resourceDescriptor = mapper.map(credentialsDescriptor);

        assertEquals("cred1", resourceDescriptor.getName());
        assertTrue(resourceDescriptor.getParentFolders().isEmpty());
    }

    @Test
    void testMap_ResourceIdWithMultipleFolders() {
        CredentialsDescriptor credentialsDescriptor = new CredentialsDescriptor("a/b/c/d/cred1", "bucketB", "locationB/");
        ResourceDescriptor resourceDescriptor = mapper.map(credentialsDescriptor);

        assertEquals("cred1", resourceDescriptor.getName());
        assertEquals(List.of("a", "b", "c", "d"), resourceDescriptor.getParentFolders());
    }
}
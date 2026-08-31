package com.epam.aidial.core.server.security;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class ResourceSecretAadTest {

    private static final ResourceDescriptor RESOURCE = new ResourceDescriptor(ResourceTypes.APPLICATION, "app",
            List.of("catalog"), "bucket", "Users/u1/", false);

    @Test
    public void testAadIsResourcePath() {
        assertArrayEquals("Users/u1/applications/catalog/app".getBytes(StandardCharsets.UTF_8),
                ResourceSecretAad.deriveFor(RESOURCE));
    }

    @Test
    public void testDescriptorAndPathAgree() {
        assertArrayEquals(ResourceSecretAad.deriveFor(RESOURCE.getAbsoluteFilePath()),
                ResourceSecretAad.deriveFor(RESOURCE));
    }

    @Test
    public void testDifferentPathsProduceDifferentAad() {
        byte[] legacy = ResourceSecretAad.deriveFor("Users/u1/applications/catalog/app");
        byte[] tenantRooted = ResourceSecretAad.deriveFor(".org/default/.users/u1/.applications/catalog/app");

        assertFalse(java.util.Arrays.equals(legacy, tenantRooted));
    }
}

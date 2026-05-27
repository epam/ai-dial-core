package com.epam.aidial.core.server.util;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ResponseIdUtilTest {

    @Test
    public void testCreateResponseId() {
        String result = ResponseIdUtil.createResponseId("gpt-4", "abc123");

        assertEquals("dial_gpt-4_abc123", result);
    }

    @Test
    public void testCreateResponseIdWithUnderscoredDeployment() {
        String result = ResponseIdUtil.createResponseId("my_model", "uuid-xyz");

        assertEquals("dial_my_model_uuid-xyz", result);
    }

    @Test
    public void testGetDescriptor() {
        ResourceDescriptor descriptor = ResponseIdUtil.getDescriptor("dial_gpt-4_abc123");

        assertEquals(ResourceTypes.RESPONSE_MAPPING, descriptor.getType());
        assertEquals(ResponseIdUtil.BUCKET, descriptor.getBucketName());
        assertEquals(ResponseIdUtil.BUCKET_LOCATION, descriptor.getBucketLocation());
        assertEquals("gpt-4", descriptor.getParentPath());
        assertEquals("abc123", descriptor.getName());
    }

    @Test
    public void testGetDescriptorRoundTrip() {
        String deploymentName = "gpt-4o";
        String uuid = "550e8400e29b41d4a716446655440000";
        String responseId = ResponseIdUtil.createResponseId(deploymentName, uuid);

        ResourceDescriptor descriptor = ResponseIdUtil.getDescriptor(responseId);

        assertEquals(deploymentName, descriptor.getParentPath());
        assertEquals(uuid, descriptor.getName());
    }

    @Test
    public void testGetDescriptorWithUnderscoredDeployment() {
        // deployment names with underscores: last underscore separates uuid
        ResourceDescriptor descriptor = ResponseIdUtil.getDescriptor("dial_my_model_uuid-xyz");

        assertEquals("my_model", descriptor.getParentPath());
        assertEquals("uuid-xyz", descriptor.getName());
    }

    @Test
    public void testGetDescriptorInvalidPrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> ResponseIdUtil.getDescriptor("chatcmpl-abc123"));
    }

    @Test
    public void testGetDescriptorMissingUnderscore() {
        // "dial_" prefix but no trailing underscore beyond prefix
        assertThrows(IllegalArgumentException.class,
                () -> ResponseIdUtil.getDescriptor("dial_nounderscore"));
    }

    @Test
    public void testGetDescriptorEmptyString() {
        assertThrows(IllegalArgumentException.class,
                () -> ResponseIdUtil.getDescriptor(""));
    }
}

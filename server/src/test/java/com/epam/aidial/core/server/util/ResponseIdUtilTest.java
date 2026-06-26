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
    public void testGetResponseMappingDescriptor() {
        ResourceDescriptor descriptor = ResponseIdUtil.getResponseMappingDescriptor("dial_gpt-4_abc123");

        assertEquals(ResourceTypes.RESPONSE_MAPPING, descriptor.getType());
        assertEquals(ResponseIdUtil.BUCKET, descriptor.getBucketName());
        assertEquals(ResponseIdUtil.BUCKET_LOCATION, descriptor.getBucketLocation());
        assertEquals("gpt-4", descriptor.getParentPath());
        assertEquals("abc123", descriptor.getName());
    }

    @Test
    public void testGetResponseMappingDescriptorRoundTrip() {
        String deploymentName = "gpt-4o";
        String uuid = "550e8400e29b41d4a716446655440000";
        String responseId = ResponseIdUtil.createResponseId(deploymentName, uuid);

        ResourceDescriptor descriptor = ResponseIdUtil.getResponseMappingDescriptor(responseId);

        assertEquals(deploymentName, descriptor.getParentPath());
        assertEquals(uuid, descriptor.getName());
    }

    @Test
    public void testGetResponseMappingDescriptorWithUnderscoredDeployment() {
        // deployment names with underscores: last underscore separates uuid
        ResourceDescriptor descriptor = ResponseIdUtil.getResponseMappingDescriptor("dial_my_model_uuid-xyz");

        assertEquals("my_model", descriptor.getParentPath());
        assertEquals("uuid-xyz", descriptor.getName());
    }

    @Test
    public void testGetResponseMappingDescriptorInvalidPrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> ResponseIdUtil.getResponseMappingDescriptor("chatcmpl-abc123"));
    }

    @Test
    public void testGetResponseMappingDescriptorMissingUnderscore() {
        // "dial_" prefix but no trailing underscore beyond prefix
        assertThrows(IllegalArgumentException.class,
                () -> ResponseIdUtil.getResponseMappingDescriptor("dial_nounderscore"));
    }

    @Test
    public void testGetResponseMappingDescriptorEmptyString() {
        assertThrows(IllegalArgumentException.class,
                () -> ResponseIdUtil.getResponseMappingDescriptor(""));
    }
}

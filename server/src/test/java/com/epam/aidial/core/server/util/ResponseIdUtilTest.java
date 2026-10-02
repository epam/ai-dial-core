package com.epam.aidial.core.server.util;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResponseIdUtilTest {

    @Test
    public void testGetResponseMetadataDescriptor() {
        String dialId = "enc_dial_id_abc123";
        ResourceDescriptor descriptor = ResponseIdUtil.getResponseMetadataDescriptor(dialId);

        assertEquals(ResourceTypes.RESPONSE_METADATA, descriptor.getType());
        assertEquals(ResponseIdUtil.RESPONSE_METADATA_BUCKET, descriptor.getBucketName());
        assertEquals(ResponseIdUtil.RESPONSE_METADATA_BUCKET_LOCATION, descriptor.getBucketLocation());
        assertEquals(dialId, descriptor.getParentPath());
        assertEquals(ResponseIdUtil.METADATA_FILE, descriptor.getName());
    }
}

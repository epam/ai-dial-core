package com.epam.aidial.core.server.util;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ResponseIdUtil {
    public static final String RESPONSE_ID_PREFIX = "dial_";

    public String createResponseId(String deploymentName, String uuid) {
        return RESPONSE_ID_PREFIX + deploymentName + "_" + uuid;
    }

    public ResourceDescriptor getResponseMappingDescriptor(String dialResponseId) {
        if (!dialResponseId.startsWith(RESPONSE_ID_PREFIX)) {
            throw new IllegalArgumentException("Invalid response id: " + dialResponseId);
        }
        int underscore = dialResponseId.lastIndexOf('_');
        if (underscore < RESPONSE_ID_PREFIX.length()) {
            throw new IllegalArgumentException("Invalid response id: " + dialResponseId);
        }
        String deploymentName = dialResponseId.substring(RESPONSE_ID_PREFIX.length(), underscore);
        String uuid = dialResponseId.substring(underscore + 1);
        String relativePath = deploymentName + "/" + uuid;
        return ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.RESPONSE_MAPPING, ResourceDescriptor.RESPONSE_MAPPINGS_BUCKET, ResourceDescriptor.RESPONSE_MAPPINGS_LOCATION, relativePath);
    }

    public ResourceDescriptor getBackgroundJobDescriptor(String jobId) {
        return ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.BACKGROUND_JOB, ResourceDescriptor.BACKGROUND_JOB_BUCKET, ResourceDescriptor.BACKGROUND_JOB_LOCATION, jobId);
    }
}

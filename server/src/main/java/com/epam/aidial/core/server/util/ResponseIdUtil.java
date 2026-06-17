package com.epam.aidial.core.server.util;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ResponseIdUtil {
    public static final String BUCKET = "response_mappings";
    public static final String BUCKET_LOCATION = BUCKET + "/";
    public static final String BACKGROUND_JOB_BUCKET = "background_jobs";
    public static final String BACKGROUND_JOB_BUCKET_LOCATION = BACKGROUND_JOB_BUCKET + "/";
    public static final String RESPONSE_ID_PREFIX = "dial_";

    public String createResponseId(String deploymentName, String uuid) {
        return RESPONSE_ID_PREFIX + deploymentName + "_" + uuid;
    }

    public ResourceDescriptor getDescriptor(String dialResponseId) {
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
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.RESPONSE_MAPPING, BUCKET, BUCKET_LOCATION, relativePath);
    }
}

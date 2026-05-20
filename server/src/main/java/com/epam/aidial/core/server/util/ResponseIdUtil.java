package com.epam.aidial.core.server.util;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ResponseIdUtil {
    public static final String BUCKET = "response_mappings";
    public static final String BUCKET_LOCATION = BUCKET + "/";

    public String createResponseId(String deploymentName, String uuid) {
        return "dial_" + deploymentName + "_" + uuid;
    }

    public ResourceDescriptor getDescriptor(String dialResponseId) {
        String prefix = "dial_";
        if (!dialResponseId.startsWith(prefix)) {
            throw new IllegalArgumentException("Invalid response id: " + dialResponseId);
        }
        int underscore = dialResponseId.lastIndexOf('_');
        if (underscore < prefix.length()) {
            throw new IllegalArgumentException("Invalid response id: " + dialResponseId);
        }
        String deploymentName = dialResponseId.substring(prefix.length(), underscore);
        String uuid = dialResponseId.substring(underscore + 1);
        String relativePath = deploymentName + "/" + uuid;
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.RESPONSE_MAPPING, BUCKET, BUCKET_LOCATION, relativePath);
    }
}

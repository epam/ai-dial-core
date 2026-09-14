package com.epam.aidial.core.server.util;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ResponseIdUtil {
    public static final String RESPONSE_MAPPINGS_BUCKET = "response_mappings";
    public static final String RESPONSE_MAPPINGS_BUCKET_LOCATION = RESPONSE_MAPPINGS_BUCKET + "/";
    public static final String BACKGROUND_JOB_BUCKET = "background_jobs";
    public static final String BACKGROUND_JOB_BUCKET_LOCATION = BACKGROUND_JOB_BUCKET + "/";
    public static final String RESPONSE_ID_PREFIX = "dial_";

    public String createResponseId(String deploymentName, String uuid) {
        return RESPONSE_ID_PREFIX + deploymentName + "_" + uuid;
    }

    public String extractDeploymentName(String dialResponseId) {
        return parse(dialResponseId).deploymentName();
    }

    public ResourceDescriptor getResponseMappingDescriptor(String dialResponseId) {
        ParsedId id = parse(dialResponseId);
        String relativePath = id.deploymentName() + "/" + id.uuid();
        return ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.RESPONSE_MAPPING, RESPONSE_MAPPINGS_BUCKET, RESPONSE_MAPPINGS_BUCKET_LOCATION, relativePath);
    }

    private ParsedId parse(String dialResponseId) {
        if (!dialResponseId.startsWith(RESPONSE_ID_PREFIX)) {
            throw new IllegalArgumentException("Invalid response id: " + dialResponseId);
        }
        int underscore = dialResponseId.lastIndexOf('_');
        if (underscore < RESPONSE_ID_PREFIX.length()) {
            throw new IllegalArgumentException("Invalid response id: " + dialResponseId);
        }
        return new ParsedId(
                dialResponseId.substring(RESPONSE_ID_PREFIX.length(), underscore),
                dialResponseId.substring(underscore + 1));
    }

    public ResourceDescriptor getBackgroundJobDescriptor(String jobId) {
        return ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.BACKGROUND_JOB, BACKGROUND_JOB_BUCKET, BACKGROUND_JOB_BUCKET_LOCATION, jobId);
    }

    private record ParsedId(String deploymentName, String uuid) {
    }
}

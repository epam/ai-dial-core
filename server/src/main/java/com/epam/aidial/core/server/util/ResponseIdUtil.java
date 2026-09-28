package com.epam.aidial.core.server.util;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ResponseIdUtil {
    public static final String RESPONSE_METADATA_BUCKET = "response_mappings";
    public static final String RESPONSE_METADATA_BUCKET_LOCATION = RESPONSE_METADATA_BUCKET + "/";
    public static final String BACKGROUND_JOB_BUCKET = "background_jobs";
    public static final String BACKGROUND_JOB_BUCKET_LOCATION = BACKGROUND_JOB_BUCKET + "/";
    public static final String METADATA_FILE = "metadata.json";

    public ResourceDescriptor getResponseMetadataDescriptor(String dialId) {
        return ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.RESPONSE_METADATA, RESPONSE_METADATA_BUCKET, RESPONSE_METADATA_BUCKET_LOCATION,
                dialId + "/" + METADATA_FILE);
    }

    public ResourceDescriptor getBackgroundJobDescriptor(String jobId) {
        return ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.BACKGROUND_JOB, BACKGROUND_JOB_BUCKET, BACKGROUND_JOB_BUCKET_LOCATION, jobId);
    }
}

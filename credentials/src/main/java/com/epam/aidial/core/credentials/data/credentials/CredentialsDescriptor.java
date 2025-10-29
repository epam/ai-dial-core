package com.epam.aidial.core.credentials.data.credentials;

import lombok.Data;

/**
 * Represents the location and identifier of a specific credentials resource
 * stored in a storage bucket.
 */
@Data
public class CredentialsDescriptor {

    private final String resourceId;
    private final String bucketName;
    private final String bucketLocation;

    public String getFullPath() {
        return bucketLocation + "/" + resourceId;
    }

}

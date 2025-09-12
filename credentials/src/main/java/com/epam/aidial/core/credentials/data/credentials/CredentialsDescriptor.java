package com.epam.aidial.core.credentials.data.credentials;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.Data;

import java.util.Arrays;
import java.util.List;

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

    public ResourceDescriptor toResourceDescriptor() {
        String[] parts = resourceId.split(ResourceDescriptor.PATH_SEPARATOR);
        String name = parts[parts.length - 1];
        List<String> parentFolders = Arrays.asList(Arrays.copyOf(parts, parts.length - 1));

        return new ResourceDescriptor(
                ResourceTypes.CREDENTIALS,
                name,
                parentFolders,
                bucketName,
                bucketLocation,
                false
        );
    }

}

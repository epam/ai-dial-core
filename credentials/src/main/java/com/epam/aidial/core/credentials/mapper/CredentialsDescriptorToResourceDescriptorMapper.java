package com.epam.aidial.core.credentials.mapper;

import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.util.Arrays;
import java.util.List;

public class CredentialsDescriptorToResourceDescriptorMapper {

    public ResourceDescriptor map(CredentialsDescriptor credentialsDescriptor) {
        String[] parts = credentialsDescriptor.getResourceId().split(ResourceDescriptor.PATH_SEPARATOR);
        String name = parts[parts.length - 1];
        List<String> parentFolders = Arrays.asList(Arrays.copyOf(parts, parts.length - 1));

        return new ResourceDescriptor(
                ResourceTypes.CREDENTIALS,
                name,
                parentFolders,
                credentialsDescriptor.getBucketName(),
                credentialsDescriptor.getBucketLocation(),
                false
        );
    }
}

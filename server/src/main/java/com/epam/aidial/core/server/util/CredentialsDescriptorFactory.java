package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.SourceType;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import lombok.experimental.UtilityClass;

import java.util.Arrays;
import java.util.List;

@UtilityClass
public class CredentialsDescriptorFactory {

    public static CredentialsDescriptor fromAnyUrl(
            String url,
            ResourceType resourceType,
            CredentialsLevel credentialsLevel,
            String userSub,
            EncryptionService encryption
    ) {

        String name;
        List<String> parentFolders;
        SourceType sourceType;
        try {
            ResourceDescriptor resourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(url, encryption);
            name = resourceDescriptor.getName();
            parentFolders = resourceDescriptor.getParentFolders();
            sourceType = SourceType.STORAGE;
        } catch (RuntimeException e) {
            if (url.startsWith(ResourceDescriptor.PATH_SEPARATOR)) {
                throw new IllegalArgumentException("Url must not start with " + ResourceDescriptor.PATH_SEPARATOR + ", but: " + url);
            }
            if (url.endsWith(ResourceDescriptor.PATH_SEPARATOR)) {
                throw new IllegalArgumentException("Url must not end with " + ResourceDescriptor.PATH_SEPARATOR + ", but: " + url);
            }

            String[] parts = url.split(ResourceDescriptor.PATH_SEPARATOR);
            name = parts[parts.length - 1];
            parentFolders = Arrays.asList(Arrays.copyOf(parts, parts.length - 1));
            sourceType = SourceType.CONFIG;
        }

        String bucketName;
        String bucketLocation;
        if (credentialsLevel == CredentialsLevel.GLOBAL) {
            bucketLocation = ResourceDescriptor.PUBLIC_LOCATION;
            bucketName = ResourceDescriptor.PUBLIC_BUCKET;
        } else if (credentialsLevel == CredentialsLevel.USER) {
            bucketLocation = BucketBuilder.USER_BUCKET_PATTERN.formatted(userSub);
            bucketName = encryption.encrypt(bucketLocation);
        } else {
            throw new IllegalArgumentException("Unsupported credentials level: " + credentialsLevel);
        }

        return CredentialsDescriptor.builder()
                .resourceId(url)
                .type(resourceType)
                .sourceType(sourceType)
                .name(name)
                .parentFolders(parentFolders)
                .bucketName(bucketName)
                .bucketLocation(bucketLocation)
                .credentialsLevel(credentialsLevel)
                .build();
    }

}

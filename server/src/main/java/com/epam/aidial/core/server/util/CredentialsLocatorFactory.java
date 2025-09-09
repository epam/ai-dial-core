package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.credentials.data.credentials.CredentialBucketLocation;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.SourceType;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@UtilityClass
public class CredentialsLocatorFactory {

    public static CredentialsLocator fromAnyUrl(
            String url,
            ResourceType resourceType,
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

        List<CredentialBucketLocation> buckets = new ArrayList<>();
        buckets.add(CredentialBucketLocation.builder()
                .credentialsLevel(CredentialsLevel.GLOBAL)
                .bucketLocation(ResourceDescriptor.PUBLIC_LOCATION)
                .bucketName(ResourceDescriptor.PUBLIC_BUCKET)
                .build());
        String userBucketLocation = BucketBuilder.USER_BUCKET_PATTERN.formatted(userSub);
        buckets.add(CredentialBucketLocation.builder()
                .credentialsLevel(CredentialsLevel.USER)
                .bucketLocation(userBucketLocation)
                .bucketName(encryption.encrypt(userBucketLocation))
                .build());

        return CredentialsLocator.builder()
                .resourceId(url)
                .type(resourceType)
                .sourceType(sourceType)
                .name(name)
                .parentFolders(parentFolders)
                .buckets(buckets)
                .build();
    }

}

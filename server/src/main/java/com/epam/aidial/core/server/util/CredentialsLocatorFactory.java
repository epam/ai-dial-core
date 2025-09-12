package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.credentials.data.credentials.CredentialBucketLocation;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.SourceType;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.util.UrlUtil;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@UtilityClass
public class CredentialsLocatorFactory {

    private static final String PATH_SEPARATOR = ResourceDescriptor.PATH_SEPARATOR;

    public static CredentialsLocator fromAnyUrl(
            String url,
            String userSub,
            EncryptionService encryption
    ) {
        ParsedResource parsedResource = tryParseResourceUrl(url, encryption)
                .orElseGet(() -> parseConfigUrl(url));

        List<CredentialBucketLocation> buckets = buildBuckets(userSub, parsedResource, encryption);

        return CredentialsLocator.builder()
                .resourceId(url)
                .type(parsedResource.resourceType())
                .sourceType(parsedResource.sourceType())
                .name(parsedResource.name())
                .parentFolders(parsedResource.parentFolders())
                .buckets(buckets)
                .build();
    }

    /**
     * Attempts to parse as a resource URL (storage-based). Returns empty if it's not valid.
     */
    private static Optional<ParsedResource> tryParseResourceUrl(String url, EncryptionService encryption) {
        try {
            return Optional.of(toParsedResource(ResourceDescriptorFactory.fromAnyUrl(url, encryption)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static ParsedResource toParsedResource(ResourceDescriptor rd) {
        return new ParsedResource(rd.getName(),
                rd.getType(),
                rd.getParentFolders(),
                SourceType.STORAGE,
                rd.getBucketName(),
                rd.getBucketLocation()
        );
    }

    /**
     * Parses a config-based resource URL.
     */
    private static ParsedResource parseConfigUrl(String url) {
        validateUrl(url);

        String[] parts = url.split(PATH_SEPARATOR);
        String name = parts[parts.length - 1];
        ResourceType resourceType = ResourceTypes.of(UrlUtil.decodePath(parts[0]));
        List<String> parentFolders = Arrays.asList(Arrays.copyOf(parts, parts.length - 1));

        return new ParsedResource(name, resourceType, parentFolders, SourceType.CONFIG, null, null);
    }

    private static void validateUrl(String url) {
        if (url.startsWith(PATH_SEPARATOR)) {
            throw new IllegalArgumentException("Url must not start with " + PATH_SEPARATOR + ": " + url);
        }
        if (url.endsWith(PATH_SEPARATOR)) {
            throw new IllegalArgumentException("Url must not end with " + PATH_SEPARATOR + ": " + url);
        }
        if (url.split(PATH_SEPARATOR).length < 2) {
            throw new IllegalArgumentException("Url has less than two segments: " + url);
        }
    }

    private static List<CredentialBucketLocation> buildBuckets(
            String userSub,
            ParsedResource parsed,
            EncryptionService encryption
    ) {
        CredentialBucketLocation personalBucket = buildPersonalBucket(userSub, encryption);
        CredentialBucketLocation globalBucket = buildGlobalBucket(parsed);

        List<CredentialBucketLocation> buckets = new ArrayList<>();
        buckets.add(personalBucket);
        if (!personalBucket.getBucketLocation().equals(globalBucket.getBucketLocation())) {
            buckets.add(globalBucket);
        }
        return buckets;
    }

    private static CredentialBucketLocation buildPersonalBucket(String userSub, EncryptionService encryption) {
        String userBucketLocation = BucketBuilder.USER_BUCKET_PATTERN.formatted(userSub);
        return CredentialBucketLocation.builder()
                .credentialsLevel(CredentialsLevel.USER)
                .bucketLocation(userBucketLocation)
                .bucketName(encryption.encrypt(userBucketLocation))
                .build();
    }

    private static CredentialBucketLocation buildGlobalBucket(ParsedResource parsed) {
        String bucketName = parsed.bucketName() != null ? parsed.bucketName() : ResourceDescriptor.PUBLIC_BUCKET;
        String bucketLocation = parsed.bucketLocation() != null ? parsed.bucketLocation() : ResourceDescriptor.PUBLIC_LOCATION;

        return CredentialBucketLocation.builder()
                .credentialsLevel(CredentialsLevel.GLOBAL)
                .bucketLocation(bucketLocation)
                .bucketName(bucketName)
                .build();
    }

    private record ParsedResource(
            String name,
            ResourceType resourceType,
            List<String> parentFolders,
            SourceType sourceType,
            String bucketName,
            String bucketLocation
    ) {}
}

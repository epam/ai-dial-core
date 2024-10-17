package com.epam.aidial.core.util;

import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import lombok.experimental.UtilityClass;

import java.util.Map;

@UtilityClass
public class ResourceUtil {
    public static final String ETAG_ATTRIBUTE = "etag";
    public static final String CREATED_AT_ATTRIBUTE = "created_at";
    public static final String UPDATED_AT_ATTRIBUTE = "updated_at";
    public static final String RESOURCE_TYPE_ATTRIBUTE = "resource_type";

    public static final String METADATA_PREFIX = "metadata/";
    // Default ETag for old records
    public static final String DEFAULT_ETAG = "0";

    public ResourceType getResourceType(String url) {
        if (url == null) {
            throw new IllegalStateException("Resource link can not be null");
        }

        String[] paths = url.split(BlobStorageUtil.PATH_SEPARATOR);

        if (paths.length < 2) {
            throw new IllegalStateException("Invalid resource link provided: " + url);
        }

        return ResourceType.of(paths[0]);
    }

    public String getBucket(String url) {
        if (url == null) {
            throw new IllegalStateException("Resource link can not be null");
        }

        String[] paths = url.split(BlobStorageUtil.PATH_SEPARATOR);

        if (paths.length < 2) {
            throw new IllegalStateException("Invalid resource link provided: " + url);
        }

        return paths[1];
    }

    public ResourceDescription resourceFromUrl(String url, EncryptionService encryptionService) {
        try {
            if (url.startsWith(METADATA_PREFIX)) {
                url = url.substring(METADATA_PREFIX.length());
            }
            return ResourceDescription.fromPrivateUrl(url, encryptionService);
        } catch (Exception e) {
            throw new IllegalArgumentException("Incorrect resource link provided " + url);
        }
    }

    public String extractEtag(Map<String, String> attributes) {
        return attributes.getOrDefault(ETAG_ATTRIBUTE, DEFAULT_ETAG);
    }
}

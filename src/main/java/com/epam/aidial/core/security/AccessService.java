package com.epam.aidial.core.security;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.service.PublicationService;
import com.epam.aidial.core.service.ShareService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.UrlUtil;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class AccessService {

    private final EncryptionService encryptionService;
    private final ShareService shareService;
    private final PublicationService publicationService;

    public boolean hasWriteAccess(String filePath, String decryptedBucket, ProxyContext context) {
        String expectedUserBucket = BlobStorageUtil.buildUserBucket(context);
        if (expectedUserBucket.equals(decryptedBucket)) {
            return true;
        }
        String expectedAppDataBucket = BlobStorageUtil.buildAppDataBucket(context);
        if (expectedAppDataBucket != null && expectedAppDataBucket.equals(decryptedBucket)) {
            return filePath.startsWith(BlobStorageUtil.APPDATA_PATTERN.formatted(UrlUtil.encodePath(context.getSourceDeployment())));
        }
        return false;
    }

    public boolean hasWriteAccess(ResourceDescription resourceDescription, ProxyContext context) {
        String parentPath = resourceDescription.getParentPath();
        String filePath;
        if (parentPath == null) {
            filePath = resourceDescription.getName();
        } else {
            filePath = resourceDescription.getParentPath() + BlobStorageUtil.PATH_SEPARATOR + resourceDescription.getName();
        }
        return hasWriteAccess(filePath, resourceDescription.getBucketLocation(), context);
    }

    public boolean isSharedResource(ResourceDescription resource, ProxyContext context) {
        String actualUserLocation = BlobStorageUtil.buildInitiatorBucket(context);
        String actualUserBucket = encryptionService.encrypt(actualUserLocation);
        return shareService != null && shareService.hasReadAccess(actualUserBucket, actualUserLocation, resource);
    }

    public boolean isReviewResource(ResourceDescription resource, ProxyContext context) {
        String actualUserLocation = BlobStorageUtil.buildInitiatorBucket(context);
        String actualUserBucket = encryptionService.encrypt(actualUserLocation);
        return publicationService != null && publicationService.hasReviewAccess(resource, actualUserBucket, actualUserLocation);
    }
}

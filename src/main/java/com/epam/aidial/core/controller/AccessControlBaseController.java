package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.UrlUtil;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public abstract class AccessControlBaseController {

    private static final String DEFAULT_RESOURCE_ERROR_MESSAGE = "Invalid resource url provided %s";

    final Proxy proxy;
    final ProxyContext context;
    final boolean checkFullAccess;

    /**
     * @param bucket url encoded bucket name
     * @param path   url encoded resource path
     */
    public Future<?> handle(String resourceType, String bucket, String path) {
        ResourceType type = ResourceType.of(resourceType);
        String urlDecodedBucket = UrlUtil.decodePath(bucket);
        String decryptedBucket = proxy.getEncryptionService().decrypt(urlDecodedBucket);

        // we should take a real user bucket not provided from resource
        String actualUserLocation = BlobStorageUtil.buildInitiatorBucket(context);
        String actualUserBucket = proxy.getEncryptionService().encrypt(actualUserLocation);

        ResourceDescription resource;
        try {
            resource = ResourceDescription.fromEncoded(type, urlDecodedBucket, decryptedBucket, path);
        } catch (Exception ex) {
            String errorMessage = ex.getMessage() != null ? ex.getMessage() : DEFAULT_RESOURCE_ERROR_MESSAGE.formatted(path);
            return context.respond(HttpStatus.BAD_REQUEST, errorMessage);
        }

        boolean hasReadAccess = isSharedWithMe(resource, type, bucket, path, actualUserBucket, actualUserLocation);
        boolean hasWriteAccess = hasWriteAccess(path, decryptedBucket);
        boolean hasAccess = checkFullAccess ? hasWriteAccess : hasReadAccess || hasWriteAccess;

        if (!hasAccess) {
            return context.respond(HttpStatus.FORBIDDEN, "You don't have an access to the bucket " + bucket);
        }

        return handle(resource);
    }

    protected abstract Future<?> handle(ResourceDescription resource);

    protected boolean isSharedWithMe(ResourceDescription resource, ResourceType type, String providedBucket, String filePath, String userBucket, String userLocation) {
        String url = type.getGroup() + BlobStorageUtil.PATH_SEPARATOR + providedBucket + BlobStorageUtil.PATH_SEPARATOR + filePath;
        return context.getApiKeyData().getAttachedFiles().contains(url)
                || (proxy.getResourceService() != null && proxy.getShareService().hasReadAccess(userBucket, userLocation, resource));
    }

    protected boolean hasWriteAccess(String filePath, String decryptedBucket) {
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

}

package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.Bucket;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class BucketController {

    private final Proxy proxy;
    private final ProxyContext context;

    public Future<?> getBucket() {
        EncryptionService encryptionService = proxy.getEncryptionService();
        String bucketLocation = BlobStorageUtil.buildUserBucket(context);
        String encryptedBucket = encryptionService.encrypt(bucketLocation);
        String appDataBucket = BlobStorageUtil.buildAppDataBucket(context);
        String appDataLocation;
        if (appDataBucket == null) {
            appDataLocation = null;
        } else {
            String encryptedAppDataBucket = encryptionService.encrypt(bucketLocation);
            appDataLocation = encryptedAppDataBucket + String.format(BlobStorageUtil.APPDATA_PATTERN.formatted(context.getSourceDeployment()));
        }
        return context.respond(HttpStatus.OK, new Bucket(encryptedBucket, appDataLocation));
    }
}

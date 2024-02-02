package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.Bucket;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.UrlUtil;
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
            String encryptedAppDataBucket = encryptionService.encrypt(appDataBucket);
            String encodedSourceDeployment = UrlUtil.encodePath(context.getSourceDeployment());
            appDataLocation = encryptedAppDataBucket + BlobStorageUtil.PATH_SEPARATOR + BlobStorageUtil.APPDATA_PATTERN.formatted(encodedSourceDeployment);
        }
        context.respond(HttpStatus.OK, new Bucket(encryptedBucket, appDataLocation));
        return Future.succeededFuture();
    }
}

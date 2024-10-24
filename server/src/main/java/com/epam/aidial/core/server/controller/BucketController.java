package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.Bucket;
import com.epam.aidial.core.server.resource.ResourceDescriptor;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.HttpStatus;
import com.epam.aidial.core.server.util.UrlUtil;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class BucketController {

    private final Proxy proxy;
    private final ProxyContext context;

    public Future<?> getBucket() {
        EncryptionService encryptionService = proxy.getEncryptionService();
        String bucketLocation = BucketBuilder.buildUserBucket(context);
        String encryptedBucket = encryptionService.encrypt(bucketLocation);
        String appDataBucket = BucketBuilder.buildAppDataBucket(context);
        String appDataLocation;
        if (appDataBucket == null) {
            appDataLocation = null;
        } else {
            String encryptedAppDataBucket = encryptionService.encrypt(appDataBucket);
            String encodedSourceDeployment = UrlUtil.encodePath(context.getSourceDeployment()); // bucket/my-app
            appDataLocation = encryptedAppDataBucket + ResourceDescriptor.PATH_SEPARATOR + BucketBuilder.APPDATA_PATTERN.formatted(encodedSourceDeployment);
        }
        return context.respond(HttpStatus.OK, new Bucket(encryptedBucket, appDataLocation));
    }
}

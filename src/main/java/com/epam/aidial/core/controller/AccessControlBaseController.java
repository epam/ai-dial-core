package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@AllArgsConstructor
public abstract class AccessControlBaseController {

    final Proxy proxy;
    final ProxyContext context;


    public Future<?> handle(String bucket, String filePath) {
        String decodedBucket = URLDecoder.decode(bucket, StandardCharsets.UTF_8);
        String decodedFilePath = URLDecoder.decode(filePath, StandardCharsets.UTF_8);
        String expectedUserBucket = BlobStorageUtil.buildUserBucket(context);
        String decryptedBucket = proxy.getEncryptionService().decrypt(decodedBucket);

        if (!expectedUserBucket.equals(decryptedBucket)) {
            return context.respond(HttpStatus.FORBIDDEN, "You don't have an access to the bucket " + decodedBucket);
        }

        return handle(decodedBucket, decryptedBucket, decodedFilePath);
    }

    protected abstract Future<?> handle(String bucketName, String bucketLocation, String filePath);

}

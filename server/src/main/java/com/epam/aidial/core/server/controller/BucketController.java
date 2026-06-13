package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.Bucket;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class BucketController {

    private final Proxy proxy;
    private final ProxyContext context;

    @ApiOperation(
            method = "GET",
            path = "/v1/bucket",
            operationId = "getUserBucket",
            tags = {"Files", "Conversations", "Prompts", "Applications", "Toolsets"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = Bucket.class)
            },
            responseProfile = ResponseProfile.AUTHENTICATED_READ_EXTENDED)
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
package com.epam.aidial.core.server.data;

import lombok.Data;

import javax.annotation.Nullable;


@Data
public class AuthBucket {

    /**
     * The encrypted bucket location for the original JWT or API_KEY.
     */
    String userBucket;
    /**
     * The bucket location for the original JWT or API_KEY.
     */
    String userBucketLocation;

    /**
     * The encrypted bucket location for the application from PER_REQUEST_KEY if present.
     */
    @Nullable
    String appBucket;

    /**
     * The bucket location for the application from PER_REQUEST_KEY if present.
     */
    @Nullable
    String appBucketLocation;
}

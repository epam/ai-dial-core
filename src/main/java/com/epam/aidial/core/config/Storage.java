package com.epam.aidial.core.config;

import lombok.Data;

import java.util.Properties;
import javax.annotation.Nullable;

@Data
public class Storage {
    /**
     * Specifies storage provider. Supported providers: s3, aws-s3, azureblob, google-cloud-storage, filesystem
     */
    String provider;
    /**
     * Optional. Specifies endpoint url for s3 compatible storages
     */
    @Nullable
    String endpoint;
    /**
     * Api key. Optional for filesystem and aws-s3 (will try to get token from EC2 instance metadata)
     */
    @Nullable
    String identity;
    /**
     * Secret key. Optional for filesystem and aws-s3 (will try to get token from EC2 instance metadata)
     */
    @Nullable
    String credential;
    /**
     * container name/root bucket
     */
    String bucket;

    /**
     * Indicates whether bucket should be created on start up
     */
    boolean createBucket;

    /**
     * Optional. Collection of key-value pairs for overrides, for example: "jclouds.filesystem.basedir": "data"
     */
    @Nullable
    Properties overrides;
}

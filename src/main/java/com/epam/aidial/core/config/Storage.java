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
     * Api key
     */
    String identity;
    /**
     * Secret key
     */
    String credential;
    /**
     * Container name/root bucket
     */
    String bucket;

    /**
     * Indicates whether bucket should be created on start up
     */
    boolean createBucket;

    /**
     * Optional. Collection of key-value pairs for overrides, for example: "jclouds.filesystem.basedir": "/tmp/data"
     */
    @Nullable
    Properties overrides;

    /**
     * Optional. Name of the root folder in a bucket, base folder for all resource. Must not contain path separators or any illegal chars
     */
    @Nullable
    String prefix;
}

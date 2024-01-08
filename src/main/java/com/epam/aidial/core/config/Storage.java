package com.epam.aidial.core.config;

import lombok.Data;

import java.util.Properties;
import javax.annotation.Nullable;

@Data
public class Storage {
    /**
     * Specifies storage provider. Supported providers: s3, aws-s3, azureblob, google-cloud-storage
     */
    String provider;
    /**
     * Optional. Specifies endpoint url for s3 compatible storages
     */
    @Nullable
    String endpoint;
    /**
     * api key
     */
    String identity;
    /**
     * secret key
     */
    String credential;
    /**
     * container name/root bucket
     */
    String bucket;

    /**
     * Indicates whether bucket should be created on start up
     */
    boolean createBucket;

    Properties overrides;
}

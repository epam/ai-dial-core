package com.epam.aidial.core.storage;

public enum StorageProvider {
    S3, AWS_S3, FILESYSTEM, GOOGLE_CLOUD_STORAGE, AZURE_BLOB;

    public static StorageProvider from(String storageProviderName) {
        return switch (storageProviderName) {
            case "s3" -> S3;
            case "aws-s3" -> AWS_S3;
            case "azureblob" -> AZURE_BLOB;
            case "google-cloud-storage" -> GOOGLE_CLOUD_STORAGE;
            case "filesystem" -> FILESYSTEM;
            default -> throw new IllegalArgumentException("Unknown storage provider");
        };
    }
}

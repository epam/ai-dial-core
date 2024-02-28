package com.epam.aidial.core.storage.credential;

import com.epam.aidial.core.storage.StorageProvider;
import lombok.experimental.UtilityClass;

@UtilityClass
public class CredentialProviderFactory {
    public static CredentialProvider create(String providerName, String identity, String credential) {
        StorageProvider provider = StorageProvider.from(providerName);
        return switch (provider) {
            case S3, AZURE_BLOB -> new DefaultCredentialProvider(identity, credential);
            case GOOGLE_CLOUD_STORAGE -> new GcpCredentialProvider(credential);
            case FILESYSTEM -> new DefaultCredentialProvider("identity", "credential");
            case AWS_S3 -> new AwsCredentialProvider(identity, credential);
        };
    }
}

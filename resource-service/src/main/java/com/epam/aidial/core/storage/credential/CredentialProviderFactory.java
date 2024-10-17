package com.epam.aidial.core.storage.credential;

import com.epam.aidial.core.storage.StorageProvider;
import lombok.experimental.UtilityClass;

@UtilityClass
public class CredentialProviderFactory {
    public static CredentialProvider create(String providerName, String identity, String credential) {
        StorageProvider provider = StorageProvider.from(providerName);
        return switch (provider) {
            case S3 -> new DefaultCredentialProvider(identity, credential);
            case AZURE_BLOB -> new AzureCredentialProvider(identity, credential);
            case GOOGLE_CLOUD_STORAGE -> new GcpCredentialProvider(identity, credential);
            case FILESYSTEM -> new DefaultCredentialProvider("identity", "credential");
            case AWS_S3 -> new AwsCredentialProvider(identity, credential);
        };
    }
}

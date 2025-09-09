package com.epam.aidial.core.credentials.service.encryption;

import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.ResourceTypes;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class ContentEncryptionKeyService {

    private static final String CEK_FILENAME = "cek";

    private final ContentEncryptionKeyManager contentEncryptionKeyManager;

    public byte[] getOrCreateKey(CredentialsDescriptor credentialsDescriptor) {
        ResourceDescriptor cekDescription = getContentEncryptionKeyDescriptor(credentialsDescriptor);
        return contentEncryptionKeyManager.getOrCreateKey(cekDescription);
    }

    public byte[] getKey(CredentialsDescriptor credentialsDescriptor) {
        ResourceDescriptor cekDescriptor = getContentEncryptionKeyDescriptor(credentialsDescriptor);
        return contentEncryptionKeyManager.getKey(cekDescriptor);
    }

    private ResourceDescriptor getContentEncryptionKeyDescriptor(CredentialsDescriptor credentialsDescriptor) {
        return new ResourceDescriptor(
                ResourceTypes.CREDENTIALS,
                CEK_FILENAME,
                List.of(),
                credentialsDescriptor.getBucketName(),
                credentialsDescriptor.getBucketLocation(),
                false
        );
    }

}

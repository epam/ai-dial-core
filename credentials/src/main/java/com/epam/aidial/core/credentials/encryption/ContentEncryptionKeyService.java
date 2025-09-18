package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.ResourceTypes;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class ContentEncryptionKeyService {

    private static final String CEK_FILENAME = "cek";

    private final ContentEncryptionKeyManager contentEncryptionKeyManager;

    public byte[] getOrCreateKey(BucketInfo bucketInfo) {
        ResourceDescriptor cekDescription = getContentEncryptionKeyDescriptor(bucketInfo);
        return contentEncryptionKeyManager.getOrCreateKey(cekDescription);
    }

    private ResourceDescriptor getContentEncryptionKeyDescriptor(BucketInfo bucketInfo) {
        return new ResourceDescriptor(
                ResourceTypes.ENCRYPTION_KEYS,
                CEK_FILENAME,
                List.of(),
                bucketInfo.name(),
                bucketInfo.location(),
                false
        );
    }

}

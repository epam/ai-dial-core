package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.ResourceTypes;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceUtil;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.function.Function;

@RequiredArgsConstructor
public class ContentEncryptionKeyService {

    private static final String CEK_FILENAME = "cek";

    private final ContentEncryptionKeyManager contentEncryptionKeyManager;
    private final Function<String, String> bucketNameEncoder;

    public byte[] getOrCreateKey(BucketInfo bucketInfo) {
        ResourceDescriptor cekDescription = getContentEncryptionKeyDescriptor(bucketInfo);
        return contentEncryptionKeyManager.getOrCreateKey(cekDescription);
    }

    /**
     * Creates a ResourceDescriptor for the Content Encryption Key (CEK).
     *
     * <p>The CEK is expected to be stored in a user bucket. If the provided
     * bucketInfo points to a subfolder within a user bucket (a publication bucket),
     * this method correctly identifies the root user bucket for the CEK.
     *
     * @param bucketInfo Information about the bucket.
     * @return A ResourceDescriptor for the CEK.
     */
    private ResourceDescriptor getContentEncryptionKeyDescriptor(BucketInfo bucketInfo) {
        String bucketLocation = ResourceUtil.getRootLocation(bucketInfo.location());
        String bucketName = bucketInfo.location().equals(bucketLocation)
                ? bucketInfo.name()
                : bucketNameEncoder.apply(bucketLocation);

        return new ResourceDescriptor(
                ResourceTypes.ENCRYPTION_KEYS,
                CEK_FILENAME,
                List.of(),
                bucketName,
                bucketLocation,
                false
        );
    }

}

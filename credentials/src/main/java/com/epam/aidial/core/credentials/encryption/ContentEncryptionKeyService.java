package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.ResourceTypes;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
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
        String[] elements = bucketInfo.location().split(ResourceDescriptor.PATH_SEPARATOR);

        String bucketLocation = bucketInfo.location();
        String bucketName = bucketInfo.name();

        if (elements.length > 2) {
            bucketLocation = elements[0] + ResourceDescriptor.PATH_SEPARATOR
                    + elements[1] + ResourceDescriptor.PATH_SEPARATOR;
            bucketName = bucketNameEncoder.apply(bucketLocation);
        }

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

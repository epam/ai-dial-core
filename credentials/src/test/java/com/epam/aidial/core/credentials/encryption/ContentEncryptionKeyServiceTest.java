package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.ResourceTypes;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentEncryptionKeyServiceTest {

    private static final String CEK_FILENAME = "cek";
    private static final byte[] EXPECTED_KEY = "test-key".getBytes();

    @Mock
    private ContentEncryptionKeyManager mockContentEncryptionKeyManager;

    @Mock
    private Function<BucketInfo, BucketInfo> rootBucketExtractor;

    private ContentEncryptionKeyService contentEncryptionKeyService;

    @BeforeEach
    void setUp() {
        contentEncryptionKeyService = new ContentEncryptionKeyService(mockContentEncryptionKeyManager, rootBucketExtractor);
    }

    @Test
    void getOrCreateKey_forRootBucket() {
        // Given
        String bucketName = "user-bucket-name";
        String bucketLocation = "Users/user";
        BucketInfo bucketInfo = new BucketInfo(bucketName, bucketLocation);
        ResourceDescriptor expectedDescriptor = new ResourceDescriptor(
                ResourceTypes.ENCRYPTION_KEYS,
                CEK_FILENAME,
                List.of(),
                bucketName,
                bucketLocation,
                false
        );
        when(rootBucketExtractor.apply(bucketInfo)).thenReturn(bucketInfo);
        when(mockContentEncryptionKeyManager.getOrCreateKey(any(ResourceDescriptor.class))).thenReturn(EXPECTED_KEY);

        // When
        byte[] result = contentEncryptionKeyService.getOrCreateKey(bucketInfo);

        // Then
        assertArrayEquals(EXPECTED_KEY, result);

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        verify(mockContentEncryptionKeyManager).getOrCreateKey(descriptorCaptor.capture());
        assertEquals(expectedDescriptor, descriptorCaptor.getValue());
    }

    @Test
    void getOrCreateKey_forDeeplyNestedPublicationBucket() {
        // Given
        BucketInfo bucketInfo = new BucketInfo("deeply-nested-bucket", "Users/user/publication-id/sub-folder/");
        BucketInfo rootBucket = new BucketInfo("user-bucket-name", "Users/user");
        when(rootBucketExtractor.apply(bucketInfo)).thenReturn(rootBucket);
        ResourceDescriptor expectedDescriptor = new ResourceDescriptor(
                ResourceTypes.ENCRYPTION_KEYS,
                CEK_FILENAME,
                List.of(),
                rootBucket.name(),
                rootBucket.location(),
                false
        );
        when(mockContentEncryptionKeyManager.getOrCreateKey(any(ResourceDescriptor.class))).thenReturn(EXPECTED_KEY);

        // When
        byte[] result = contentEncryptionKeyService.getOrCreateKey(bucketInfo);

        // Then
        assertArrayEquals(EXPECTED_KEY, result);

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        verify(mockContentEncryptionKeyManager).getOrCreateKey(descriptorCaptor.capture());
        assertEquals(expectedDescriptor, descriptorCaptor.getValue());
    }

}
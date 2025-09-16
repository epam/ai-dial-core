package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.encryption.CredentialEncryptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceAuthSettingsEncryptionServiceTest {

    private static final String RESOURCE_ID = "resource-123";
    private static final byte[] AAD = RESOURCE_ID.getBytes(StandardCharsets.UTF_8);

    private static final String CLIENT_SECRET = "plain-client-secret";
    private static final String API_KEY_HEADER = "plain-api-key";
    private static final byte[] ENCRYPTED_CLIENT_SECRET = "encrypted-client-secret".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ENCRYPTED_API_KEY = "encrypted-api-key".getBytes(StandardCharsets.UTF_8);

    @Mock
    private CredentialEncryptionService encryptionService;

    @InjectMocks
    private ResourceAuthSettingsEncryptionService service;

    @Test
    void testEncrypt_encryptsAllNonNullFields() {
        BucketInfo bucketInfo = new BucketInfo("bucket-name", "bucket-location/");
        ResourceAuthSettings settings = new ResourceAuthSettings();
        settings.setClientSecret(CLIENT_SECRET);
        settings.setApiKeyHeader(API_KEY_HEADER);

        when(encryptionService.encrypt(bucketInfo, CLIENT_SECRET.getBytes(StandardCharsets.UTF_8), AAD))
                .thenReturn(ENCRYPTED_CLIENT_SECRET);
        when(encryptionService.encrypt(bucketInfo, API_KEY_HEADER.getBytes(StandardCharsets.UTF_8), AAD))
                .thenReturn(ENCRYPTED_API_KEY);

        service.encrypt(RESOURCE_ID, bucketInfo, settings);

        String expectedClientSecretEncoded = Base64.getEncoder().encodeToString(ENCRYPTED_CLIENT_SECRET);
        String expectedApiKeyEncoded = Base64.getEncoder().encodeToString(ENCRYPTED_API_KEY);

        assertEquals(expectedClientSecretEncoded, settings.getClientSecret());
        assertEquals(expectedApiKeyEncoded, settings.getApiKeyHeader());

        verify(encryptionService).encrypt(bucketInfo, CLIENT_SECRET.getBytes(StandardCharsets.UTF_8), AAD);
        verify(encryptionService).encrypt(bucketInfo, API_KEY_HEADER.getBytes(StandardCharsets.UTF_8), AAD);
        verifyNoMoreInteractions(encryptionService);
    }

    @Test
    void testEncrypt_skipsNullFields() {
        BucketInfo bucketInfo = new BucketInfo("bucket-name", "bucket-location/");
        ResourceAuthSettings settings = new ResourceAuthSettings();
        settings.setClientSecret(null);
        settings.setApiKeyHeader(API_KEY_HEADER);

        when(encryptionService.encrypt(bucketInfo, API_KEY_HEADER.getBytes(StandardCharsets.UTF_8), AAD))
                .thenReturn(ENCRYPTED_API_KEY);

        service.encrypt(RESOURCE_ID, bucketInfo, settings);

        assertNull(settings.getClientSecret());
        assertEquals(Base64.getEncoder().encodeToString(ENCRYPTED_API_KEY), settings.getApiKeyHeader());
        verify(encryptionService, times(1))
                .encrypt(bucketInfo, API_KEY_HEADER.getBytes(StandardCharsets.UTF_8), AAD);
    }

    @Test
    void testDecrypt_decryptsAllNonNullFields() {
        BucketInfo bucketInfo = new BucketInfo("bucket-name", "bucket-location/");
        ResourceAuthSettings settings = new ResourceAuthSettings();
        settings.setClientSecret(Base64.getEncoder().encodeToString(ENCRYPTED_CLIENT_SECRET));
        settings.setApiKeyHeader(Base64.getEncoder().encodeToString(ENCRYPTED_API_KEY));

        when(encryptionService.decrypt(bucketInfo, ENCRYPTED_CLIENT_SECRET, AAD))
                .thenReturn(CLIENT_SECRET.getBytes(StandardCharsets.UTF_8));
        when(encryptionService.decrypt(bucketInfo, ENCRYPTED_API_KEY, AAD))
                .thenReturn(API_KEY_HEADER.getBytes(StandardCharsets.UTF_8));

        service.decrypt(RESOURCE_ID, bucketInfo, settings);

        assertEquals(CLIENT_SECRET, settings.getClientSecret());
        assertEquals(API_KEY_HEADER, settings.getApiKeyHeader());

        verify(encryptionService).decrypt(bucketInfo, ENCRYPTED_CLIENT_SECRET, AAD);
        verify(encryptionService).decrypt(bucketInfo, ENCRYPTED_API_KEY, AAD);
    }

    @Test
    void testDecrypt_skipsNullFields() {
        BucketInfo bucketInfo = new BucketInfo("bucket-name", "bucket-location/");
        ResourceAuthSettings settings = new ResourceAuthSettings();
        settings.setClientSecret(null);
        settings.setApiKeyHeader(Base64.getEncoder().encodeToString(ENCRYPTED_API_KEY));

        when(encryptionService.decrypt(bucketInfo, ENCRYPTED_API_KEY, AAD))
                .thenReturn(API_KEY_HEADER.getBytes(StandardCharsets.UTF_8));

        service.decrypt(RESOURCE_ID, bucketInfo, settings);

        assertNull(settings.getClientSecret());
        assertEquals(API_KEY_HEADER, settings.getApiKeyHeader());
        verify(encryptionService).decrypt(bucketInfo, ENCRYPTED_API_KEY, AAD);
    }

    @Test
    void testEncryptAndDecrypt_areInverseOperations() {
        BucketInfo bucketInfo = new BucketInfo("bucket-name", "bucket-location/");
        ResourceAuthSettings original = new ResourceAuthSettings();
        original.setClientSecret(CLIENT_SECRET);
        original.setApiKeyHeader(API_KEY_HEADER);

        when(encryptionService.encrypt(any(), any(), any()))
                .thenAnswer(inv -> {
                    byte[] input = inv.getArgument(1);
                    return ("enc_" + new String(input, StandardCharsets.UTF_8))
                            .getBytes(StandardCharsets.UTF_8);
                });

        when(encryptionService.decrypt(any(), any(), any()))
                .thenAnswer(inv -> {
                    byte[] encrypted = inv.getArgument(1);
                    String str = new String(encrypted, StandardCharsets.UTF_8);
                    return str.replace("enc_", "").getBytes(StandardCharsets.UTF_8);
                });

        service.encrypt(RESOURCE_ID, bucketInfo, original);
        service.decrypt(RESOURCE_ID, bucketInfo, original);

        assertEquals(CLIENT_SECRET, original.getClientSecret());
        assertEquals(API_KEY_HEADER, original.getApiKeyHeader());
    }

}
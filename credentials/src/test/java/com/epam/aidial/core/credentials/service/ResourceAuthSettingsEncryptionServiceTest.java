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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceAuthSettingsEncryptionServiceTest {

    private static final String RESOURCE_ID = "resource-123";
    private static final byte[] AAD = RESOURCE_ID.getBytes(StandardCharsets.UTF_8);

    private static final String CLIENT_SECRET = "plain-client-secret";
    private static final byte[] ENCRYPTED_CLIENT_SECRET = "encrypted-client-secret".getBytes(StandardCharsets.UTF_8);

    private static final String CODE_VERIFIER = "plain-code-verifier";
    private static final byte[] ENCRYPTED_CODE_VERIFIER = "encrypted-code-verifier".getBytes(StandardCharsets.UTF_8);
    // PKCE-style verifier with URL-safe chars '-' and '_' that fail standard Base64.getDecoder().decode().
    private static final String LEGACY_PLAINTEXT_CODE_VERIFIER = "abc-_def-ghijklmnopqrstuvwxyz0123456789-_-_-_X";

    @Mock
    private CredentialEncryptionService encryptionService;

    @InjectMocks
    private ResourceAuthSettingsEncryptionService service;

    @Test
    void testEncrypt_encryptsNonNullField() {
        BucketInfo bucketInfo = new BucketInfo("bucket-name", "bucket-location/");
        ResourceAuthSettings settings = new ResourceAuthSettings();
        settings.setClientSecret(CLIENT_SECRET);

        when(encryptionService.encrypt(bucketInfo, CLIENT_SECRET.getBytes(StandardCharsets.UTF_8), AAD))
                .thenReturn(ENCRYPTED_CLIENT_SECRET);

        service.encrypt(RESOURCE_ID, bucketInfo, settings);

        String expectedClientSecretEncoded = Base64.getEncoder().encodeToString(ENCRYPTED_CLIENT_SECRET);
        assertEquals(expectedClientSecretEncoded, settings.getClientSecret());

        verify(encryptionService).encrypt(bucketInfo, CLIENT_SECRET.getBytes(StandardCharsets.UTF_8), AAD);
        verifyNoMoreInteractions(encryptionService);
    }

    @Test
    void testEncrypt_skipsNullField() {
        BucketInfo bucketInfo = new BucketInfo("bucket-name", "bucket-location/");
        ResourceAuthSettings settings = new ResourceAuthSettings();
        settings.setClientSecret(null);

        service.encrypt(RESOURCE_ID, bucketInfo, settings);

        assertNull(settings.getClientSecret());
        verifyNoInteractions(encryptionService);
    }

    @Test
    void testDecrypt_decryptsAllNonNullFields() {
        BucketInfo bucketInfo = new BucketInfo("bucket-name", "bucket-location/");
        ResourceAuthSettings settings = new ResourceAuthSettings();
        settings.setClientSecret(Base64.getEncoder().encodeToString(ENCRYPTED_CLIENT_SECRET));

        when(encryptionService.decrypt(bucketInfo, ENCRYPTED_CLIENT_SECRET, AAD))
                .thenReturn(CLIENT_SECRET.getBytes(StandardCharsets.UTF_8));

        service.decrypt(RESOURCE_ID, bucketInfo, settings);

        assertEquals(CLIENT_SECRET, settings.getClientSecret());
        verify(encryptionService).decrypt(bucketInfo, ENCRYPTED_CLIENT_SECRET, AAD);
    }

    @Test
    void testDecrypt_skipsNullFields() {
        BucketInfo bucketInfo = new BucketInfo("bucket-name", "bucket-location/");
        ResourceAuthSettings settings = new ResourceAuthSettings();
        settings.setClientSecret(null);

        service.decrypt(RESOURCE_ID, bucketInfo, settings);

        assertNull(settings.getClientSecret());
        verifyNoInteractions(encryptionService);
    }

    @Test
    void testEncrypt_encryptsCodeVerifier() {
        BucketInfo bucketInfo = new BucketInfo("bucket-name", "bucket-location/");
        ResourceAuthSettings settings = new ResourceAuthSettings();
        settings.setCodeVerifier(CODE_VERIFIER);

        when(encryptionService.encrypt(bucketInfo, CODE_VERIFIER.getBytes(StandardCharsets.UTF_8), AAD))
                .thenReturn(ENCRYPTED_CODE_VERIFIER);

        service.encrypt(RESOURCE_ID, bucketInfo, settings);

        String expected = Base64.getEncoder().encodeToString(ENCRYPTED_CODE_VERIFIER);
        assertEquals(expected, settings.getCodeVerifier());
    }

    @Test
    void testDecrypt_decryptsBase64CodeVerifier() {
        BucketInfo bucketInfo = new BucketInfo("bucket-name", "bucket-location/");
        ResourceAuthSettings settings = new ResourceAuthSettings();
        settings.setCodeVerifier(Base64.getEncoder().encodeToString(ENCRYPTED_CODE_VERIFIER));

        when(encryptionService.minEncryptedLength()).thenReturn(1);
        when(encryptionService.decrypt(bucketInfo, ENCRYPTED_CODE_VERIFIER, AAD))
                .thenReturn(CODE_VERIFIER.getBytes(StandardCharsets.UTF_8));

        service.decrypt(RESOURCE_ID, bucketInfo, settings);

        assertEquals(CODE_VERIFIER, settings.getCodeVerifier());
    }

    @Test
    void testDecrypt_returnsLegacyPlaintextCodeVerifierAsIs() {
        BucketInfo bucketInfo = new BucketInfo("bucket-name", "bucket-location/");
        ResourceAuthSettings settings = new ResourceAuthSettings();
        settings.setCodeVerifier(LEGACY_PLAINTEXT_CODE_VERIFIER);

        service.decrypt(RESOURCE_ID, bucketInfo, settings);

        assertEquals(LEGACY_PLAINTEXT_CODE_VERIFIER, settings.getCodeVerifier());
        verifyNoInteractions(encryptionService);
    }

    @Test
    void testDecrypt_returnsShortValidBase64CodeVerifierAsIs() {
        // "abcdABCD12345678" is valid Base64 decoding to 12 bytes, below the 28-byte new-format minimum.
        String shortValidBase64 = "abcdABCD12345678";
        BucketInfo bucketInfo = new BucketInfo("bucket-name", "bucket-location/");
        ResourceAuthSettings settings = new ResourceAuthSettings();
        settings.setCodeVerifier(shortValidBase64);

        when(encryptionService.minEncryptedLength()).thenReturn(28);

        service.decrypt(RESOURCE_ID, bucketInfo, settings);

        assertEquals(shortValidBase64, settings.getCodeVerifier());
        verify(encryptionService, never()).decrypt(any(), any(), any());
    }

    @Test
    void testDecrypt_returnsLongValidBase64CodeVerifierAsIsWhenDecryptFails() {
        // 48-char alphanumeric value: valid Base64 decoding to 36 bytes (>= 28), so decrypt is attempted.
        String longValidBase64 = "abcdABCD12345678abcdABCD12345678abcdABCD12345678";
        BucketInfo bucketInfo = new BucketInfo("bucket-name", "bucket-location/");
        ResourceAuthSettings settings = new ResourceAuthSettings();
        settings.setCodeVerifier(longValidBase64);

        when(encryptionService.minEncryptedLength()).thenReturn(28);
        when(encryptionService.decrypt(any(), any(), any()))
                .thenThrow(new RuntimeException("authentication tag mismatch"));

        service.decrypt(RESOURCE_ID, bucketInfo, settings);

        assertEquals(longValidBase64, settings.getCodeVerifier());
        verify(encryptionService).decrypt(any(), any(), any());
    }

    @Test
    void testEncryptAndDecrypt_areInverseOperationsForCodeVerifier() {
        BucketInfo bucketInfo = new BucketInfo("bucket-name", "bucket-location/");
        ResourceAuthSettings original = new ResourceAuthSettings();
        original.setCodeVerifier(CODE_VERIFIER);

        when(encryptionService.minEncryptedLength()).thenReturn(1);
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

        assertEquals(CODE_VERIFIER, original.getCodeVerifier());
    }

    @Test
    void testProcessFields_handlesClientSecretAndCodeVerifierTogether() {
        BucketInfo bucketInfo = new BucketInfo("bucket-name", "bucket-location/");
        ResourceAuthSettings settings = new ResourceAuthSettings();
        settings.setClientSecret(CLIENT_SECRET);
        settings.setCodeVerifier(CODE_VERIFIER);

        when(encryptionService.encrypt(bucketInfo, CLIENT_SECRET.getBytes(StandardCharsets.UTF_8), AAD))
                .thenReturn(ENCRYPTED_CLIENT_SECRET);
        when(encryptionService.encrypt(bucketInfo, CODE_VERIFIER.getBytes(StandardCharsets.UTF_8), AAD))
                .thenReturn(ENCRYPTED_CODE_VERIFIER);

        service.encrypt(RESOURCE_ID, bucketInfo, settings);

        assertEquals(Base64.getEncoder().encodeToString(ENCRYPTED_CLIENT_SECRET), settings.getClientSecret());
        assertEquals(Base64.getEncoder().encodeToString(ENCRYPTED_CODE_VERIFIER), settings.getCodeVerifier());
    }

    @Test
    void testEncryptAndDecrypt_areInverseOperations() {
        BucketInfo bucketInfo = new BucketInfo("bucket-name", "bucket-location/");
        ResourceAuthSettings original = new ResourceAuthSettings();
        original.setClientSecret(CLIENT_SECRET);

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
    }

}
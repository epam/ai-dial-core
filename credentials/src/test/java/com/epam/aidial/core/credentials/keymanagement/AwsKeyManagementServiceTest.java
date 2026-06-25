package com.epam.aidial.core.credentials.keymanagement;

import com.epam.aidial.core.credentials.exception.EncryptionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptResponse;
import software.amazon.awssdk.services.kms.model.IncorrectKeyException;
import software.amazon.awssdk.services.kms.model.InvalidCiphertextException;
import software.amazon.awssdk.services.kms.model.InvalidKeyUsageException;
import software.amazon.awssdk.services.kms.model.KmsInvalidStateException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AwsKeyManagementServiceTest {

    private static final String KEY_ID = "test-key-id";
    private static final String ENCRYPTION_ALGORITHM = "SYMMETRIC_DEFAULT";
    private static final byte[] PLAINTEXT = "test-plaintext".getBytes();
    private static final byte[] ENCRYPTED_BLOB = "encrypted-blob".getBytes();

    @Mock
    private KmsClient mockKms;

    private AwsKeyManagementService awsKeyManagementService;

    @BeforeEach
    void setUp() {
        awsKeyManagementService = new AwsKeyManagementService(mockKms, KEY_ID, ENCRYPTION_ALGORITHM);
    }

    @Test
    void testEncrypt_success() {
        // Given
        EncryptResponse encryptResult = EncryptResponse.builder()
                .ciphertextBlob(SdkBytes.fromByteArray(ENCRYPTED_BLOB))
                .build();
        when(mockKms.encrypt(any(EncryptRequest.class))).thenReturn(encryptResult);

        // When
        byte[] result = awsKeyManagementService.encrypt(PLAINTEXT);

        // Then
        assertNotNull(result);
        assertArrayEquals(ENCRYPTED_BLOB, result);

        ArgumentCaptor<EncryptRequest> requestCaptor = ArgumentCaptor.forClass(EncryptRequest.class);
        verify(mockKms).encrypt(requestCaptor.capture());

        EncryptRequest capturedRequest = requestCaptor.getValue();
        assertEquals(KEY_ID, capturedRequest.keyId());
        assertEquals(ENCRYPTION_ALGORITHM, capturedRequest.encryptionAlgorithmAsString());
        assertArrayEquals(PLAINTEXT, capturedRequest.plaintext().asByteArray());
    }

    @Test
    void testEncrypt_plaintextTooLarge() {
        // Given
        byte[] largePlaintext = new byte[4097];

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            awsKeyManagementService.encrypt(largePlaintext);
        });

        assertEquals("Plaintext too large for direct KMS Encrypt (max 4096 bytes).", exception.getMessage());
    }

    @Test
    void testEncrypt_nullPlaintext() {
        // When & Then
        assertThrows(NullPointerException.class, () -> {
            awsKeyManagementService.encrypt(null);
        });
    }

    @Test
    void testDecrypt_success() {
        // Given
        DecryptResponse decryptResult = DecryptResponse.builder()
                .plaintext(SdkBytes.fromByteArray(PLAINTEXT))
                .build();
        when(mockKms.decrypt(any(DecryptRequest.class))).thenReturn(decryptResult);

        // When
        byte[] result = awsKeyManagementService.decrypt(ENCRYPTED_BLOB);

        // Then
        assertNotNull(result);
        assertArrayEquals(PLAINTEXT, result);

        ArgumentCaptor<DecryptRequest> requestCaptor = ArgumentCaptor.forClass(DecryptRequest.class);
        verify(mockKms).decrypt(requestCaptor.capture());

        DecryptRequest capturedRequest = requestCaptor.getValue();
        assertEquals(KEY_ID, capturedRequest.keyId());
        assertEquals(ENCRYPTION_ALGORITHM, capturedRequest.encryptionAlgorithmAsString());
        assertArrayEquals(ENCRYPTED_BLOB, capturedRequest.ciphertextBlob().asByteArray());
    }

    @Test
    void testDecrypt_nullEncrypted() {
        // When & Then
        assertThrows(NullPointerException.class, () -> {
            awsKeyManagementService.decrypt(null);
        });
    }

    @Test
    void testConstructor_nullKms() {
        // When & Then
        assertThrows(NullPointerException.class, () -> {
            new AwsKeyManagementService(null, KEY_ID, ENCRYPTION_ALGORITHM);
        });
    }

    @Test
    void testConstructor_nullKeyId() {
        // When & Then
        assertThrows(NullPointerException.class, () -> {
            new AwsKeyManagementService(mockKms, null, ENCRYPTION_ALGORITHM);
        });
    }

    @Test
    void testEncrypt_kmsThrowsInvalidKeyUsageException() {
        when(mockKms.encrypt(any(EncryptRequest.class)))
                .thenThrow(InvalidKeyUsageException.builder().message("Invalid key usage").build());
        assertThrows(EncryptionException.class, () -> awsKeyManagementService.encrypt(PLAINTEXT));
    }

    @Test
    void testEncrypt_kmsThrowsKmsInvalidStateException() {
        when(mockKms.encrypt(any(EncryptRequest.class)))
                .thenThrow(KmsInvalidStateException.builder().message("KMS is in an invalid state").build());
        assertThrows(EncryptionException.class, () -> awsKeyManagementService.encrypt(PLAINTEXT));
    }

    @Test
    void testDecrypt_kmsThrowsInvalidCiphertextException() {
        when(mockKms.decrypt(any(DecryptRequest.class)))
                .thenThrow(InvalidCiphertextException.builder().message("Invalid ciphertext provided").build());
        assertThrows(EncryptionException.class, () -> awsKeyManagementService.decrypt(ENCRYPTED_BLOB));
    }

    @Test
    void testDecrypt_kmsThrowsInvalidKeyUsageException() {
        when(mockKms.decrypt(any(DecryptRequest.class)))
                .thenThrow(InvalidKeyUsageException.builder().message("Invalid key usage").build());
        assertThrows(EncryptionException.class, () -> awsKeyManagementService.decrypt(ENCRYPTED_BLOB));
    }

    @Test
    void testDecrypt_kmsThrowsIncorrectKeyException() {
        when(mockKms.decrypt(any(DecryptRequest.class)))
                .thenThrow(IncorrectKeyException.builder().message("Incorrect key was used").build());
        assertThrows(EncryptionException.class, () -> awsKeyManagementService.decrypt(ENCRYPTED_BLOB));
    }

    @Test
    void testDecrypt_kmsThrowsKmsInvalidStateException() {
        when(mockKms.decrypt(any(DecryptRequest.class)))
                .thenThrow(KmsInvalidStateException.builder().message("KMS is in an invalid state").build());
        assertThrows(EncryptionException.class, () -> awsKeyManagementService.decrypt(ENCRYPTED_BLOB));
    }
}
package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CredentialEncryptionServiceTest {

    @Mock
    private ContentEncryptionKeyService contentEncryptionKeyService;

    @Mock
    private DataEncryptionService dataEncryptionService;

    @InjectMocks
    private CredentialEncryptionService credentialEncryptionService;

    private static final byte[] CEK = "cek".getBytes();
    private static final byte[] PLAINTEXT = "plain".getBytes();
    private static final byte[] CIPHERTEXT = "cipher".getBytes();
    private static final byte[] AAD = "aad".getBytes();

    private static final BucketInfo BUCKET_INFO = new BucketInfo("bucket", "location");


    @Test
    void encrypt_successfulOnFirstAttempt() {
        // Given
        when(contentEncryptionKeyService.getOrCreateKey(BUCKET_INFO)).thenReturn(CEK);
        when(dataEncryptionService.encrypt(PLAINTEXT, CEK, AAD)).thenReturn(CIPHERTEXT);

        // When
        byte[] result = credentialEncryptionService.encrypt(BUCKET_INFO, PLAINTEXT, AAD);

        // Then
        assertArrayEquals(CIPHERTEXT, result);
        verify(contentEncryptionKeyService).getOrCreateKey(BUCKET_INFO);
        verify(dataEncryptionService).encrypt(PLAINTEXT, CEK, AAD);
    }

    @Test
    void decrypt_successfulOnFirstAttempt() {
        // Given
        when(contentEncryptionKeyService.getOrCreateKey(BUCKET_INFO)).thenReturn(CEK);
        when(dataEncryptionService.decrypt(CIPHERTEXT, CEK, AAD)).thenReturn(PLAINTEXT);

        // When
        byte[] result = credentialEncryptionService.decrypt(BUCKET_INFO, CIPHERTEXT, AAD);

        // Then
        assertArrayEquals(PLAINTEXT, result);
        verify(contentEncryptionKeyService).getOrCreateKey(BUCKET_INFO);
        verify(dataEncryptionService).decrypt(CIPHERTEXT, CEK, AAD);
    }
}

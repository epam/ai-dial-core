package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.exception.CekEncryptionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

    private static final byte[] INITIAL_KEY = "initial-key".getBytes();
    private static final byte[] NEW_KEY = "new-key".getBytes();
    private static final byte[] PLAINTEXT = "plain".getBytes();
    private static final byte[] CIPHERTEXT = "cipher".getBytes();
    private static final byte[] NEW_CIPHERTEXT = "new-cipher".getBytes();
    private static final byte[] AAD = "aad".getBytes();

    private static final BucketInfo BUCKET_INFO = new BucketInfo("bucket", "location");


    @Test
    void encrypt_successfulOnFirstAttempt() {
        // Given
        when(contentEncryptionKeyService.getOrCreateKey(BUCKET_INFO)).thenReturn(INITIAL_KEY);
        when(dataEncryptionService.encrypt(PLAINTEXT, INITIAL_KEY, AAD)).thenReturn(CIPHERTEXT);

        // When
        byte[] result = credentialEncryptionService.encrypt(BUCKET_INFO, PLAINTEXT, AAD);

        // Then
        assertArrayEquals(CIPHERTEXT, result);
        verify(contentEncryptionKeyService).getOrCreateKey(BUCKET_INFO);
        verify(dataEncryptionService).encrypt(PLAINTEXT, INITIAL_KEY, AAD);
        verify(contentEncryptionKeyService, never()).createKey(any());
        verify(dataEncryptionService, never()).encrypt(PLAINTEXT, NEW_KEY, AAD);
    }

    @Test
    void encrypt_retriesWithNewKeyOnSecurityException() {
        // Given
        when(contentEncryptionKeyService.getOrCreateKey(BUCKET_INFO)).thenReturn(INITIAL_KEY);
        when(dataEncryptionService.encrypt(PLAINTEXT, INITIAL_KEY, AAD))
                .thenThrow(new CekEncryptionException("Key invalid"));
        when(contentEncryptionKeyService.createKey(BUCKET_INFO)).thenReturn(NEW_KEY);
        when(dataEncryptionService.encrypt(PLAINTEXT, NEW_KEY, AAD)).thenReturn(NEW_CIPHERTEXT);

        // When
        byte[] result = credentialEncryptionService.encrypt(BUCKET_INFO, PLAINTEXT, AAD);

        // Then
        assertArrayEquals(NEW_CIPHERTEXT, result);
        verify(contentEncryptionKeyService).getOrCreateKey(BUCKET_INFO);
        verify(dataEncryptionService).encrypt(PLAINTEXT, INITIAL_KEY, AAD);
        verify(contentEncryptionKeyService).createKey(BUCKET_INFO);
        verify(dataEncryptionService).encrypt(PLAINTEXT, NEW_KEY, AAD);
    }

    @Test
    void decrypt_successfulOnFirstAttempt() {
        // Given
        when(contentEncryptionKeyService.getOrCreateKey(BUCKET_INFO)).thenReturn(INITIAL_KEY);
        when(dataEncryptionService.decrypt(CIPHERTEXT, INITIAL_KEY, AAD)).thenReturn(PLAINTEXT);

        // When
        byte[] result = credentialEncryptionService.decrypt(BUCKET_INFO, CIPHERTEXT, AAD);

        // Then
        assertArrayEquals(PLAINTEXT, result);
        verify(contentEncryptionKeyService).getOrCreateKey(BUCKET_INFO);
        verify(dataEncryptionService).decrypt(CIPHERTEXT, INITIAL_KEY, AAD);
        verify(contentEncryptionKeyService, never()).createKey(any());
        verify(dataEncryptionService, never()).decrypt(CIPHERTEXT, NEW_KEY, AAD);
    }

    @Test
    void decrypt_retriesWithNewKeyOnSecurityException() {
        // Given
        when(contentEncryptionKeyService.getOrCreateKey(BUCKET_INFO)).thenReturn(INITIAL_KEY);
        when(dataEncryptionService.decrypt(CIPHERTEXT, INITIAL_KEY, AAD))
                .thenThrow(new CekEncryptionException("Key invalid"));
        when(contentEncryptionKeyService.createKey(BUCKET_INFO)).thenReturn(NEW_KEY);
        when(dataEncryptionService.decrypt(CIPHERTEXT, NEW_KEY, AAD)).thenReturn(PLAINTEXT);

        // When
        byte[] result = credentialEncryptionService.decrypt(BUCKET_INFO, CIPHERTEXT, AAD);

        // Then
        assertArrayEquals(PLAINTEXT, result);
        verify(contentEncryptionKeyService).getOrCreateKey(BUCKET_INFO);
        verify(dataEncryptionService).decrypt(CIPHERTEXT, INITIAL_KEY, AAD);
        verify(contentEncryptionKeyService).createKey(BUCKET_INFO);
        verify(dataEncryptionService).decrypt(CIPHERTEXT, NEW_KEY, AAD);
    }
}

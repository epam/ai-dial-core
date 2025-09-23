package com.epam.aidial.core.credentials.keymanagement;

import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import com.azure.security.keyvault.keys.cryptography.models.UnwrapResult;
import com.azure.security.keyvault.keys.cryptography.models.WrapResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AzureKeyManagementServiceTest {

    private static final byte[] PLAINTEXT = "test-plaintext-for-azure".getBytes();
    private static final byte[] ENCRYPTED_DATA = "encrypted-data-from-azure".getBytes();
    private static final KeyWrapAlgorithm KEY_WRAP_ALGORITHM = KeyWrapAlgorithm.RSA_OAEP;

    @Mock
    private CryptographyClient mockCryptoClient;

    private AzureKeyManagementService azureKeyManagementService;

    @BeforeEach
    void setUp() {
        azureKeyManagementService = new AzureKeyManagementService(mockCryptoClient, KEY_WRAP_ALGORITHM);
    }

    @Test
    void testEncrypt_success() {
        // Given
        WrapResult wrapResult = new WrapResult(ENCRYPTED_DATA, KEY_WRAP_ALGORITHM, "test-key-id");
        when(mockCryptoClient.wrapKey(eq(KEY_WRAP_ALGORITHM), eq(PLAINTEXT))).thenReturn(wrapResult);

        // When
        byte[] result = azureKeyManagementService.encrypt(PLAINTEXT);

        // Then
        assertNotNull(result);
        assertArrayEquals(ENCRYPTED_DATA, result);

        ArgumentCaptor<KeyWrapAlgorithm> algorithmCaptor = ArgumentCaptor.forClass(KeyWrapAlgorithm.class);
        ArgumentCaptor<byte[]> plaintextCaptor = ArgumentCaptor.forClass(byte[].class);

        verify(mockCryptoClient).wrapKey(algorithmCaptor.capture(), plaintextCaptor.capture());

        assertEquals(KEY_WRAP_ALGORITHM, algorithmCaptor.getValue());
        assertArrayEquals(PLAINTEXT, plaintextCaptor.getValue());
    }

    @Test
    void testEncrypt_nullPlaintext() {
        // When & Then
        NullPointerException exception = assertThrows(NullPointerException.class, () -> {
            azureKeyManagementService.encrypt(null);
        });
        assertEquals("plain cannot be null.", exception.getMessage());
    }

    @Test
    void testDecrypt_success() {
        // Given
        UnwrapResult unwrapResult = new UnwrapResult(PLAINTEXT, KEY_WRAP_ALGORITHM, "test-key-id");
        when(mockCryptoClient.unwrapKey(eq(KEY_WRAP_ALGORITHM), eq(ENCRYPTED_DATA))).thenReturn(unwrapResult);

        // When
        byte[] result = azureKeyManagementService.decrypt(ENCRYPTED_DATA);

        // Then
        assertNotNull(result);
        assertArrayEquals(PLAINTEXT, result);

        ArgumentCaptor<KeyWrapAlgorithm> algorithmCaptor = ArgumentCaptor.forClass(KeyWrapAlgorithm.class);
        ArgumentCaptor<byte[]> encryptedCaptor = ArgumentCaptor.forClass(byte[].class);

        verify(mockCryptoClient).unwrapKey(algorithmCaptor.capture(), encryptedCaptor.capture());

        assertEquals(KEY_WRAP_ALGORITHM, algorithmCaptor.getValue());
        assertArrayEquals(ENCRYPTED_DATA, encryptedCaptor.getValue());
    }

    @Test
    void testDecrypt_nullEncrypted() {
        // When & Then
        NullPointerException exception = assertThrows(NullPointerException.class, () -> {
            azureKeyManagementService.decrypt(null);
        });
        assertEquals("encrypted cannot be null.", exception.getMessage());
    }

    @Test
    void testConstructor_nullCryptoClient() {
        // When & Then
        NullPointerException exception = assertThrows(NullPointerException.class, () -> {
            new AzureKeyManagementService(null, KEY_WRAP_ALGORITHM);
        });
        assertEquals("cryptoClient cannot be null.", exception.getMessage());
    }

    @Test
    void testConstructor_nullKeyWrapAlgorithm() {
        // When & Then
        NullPointerException exception = assertThrows(NullPointerException.class, () -> {
            new AzureKeyManagementService(mockCryptoClient, null);
        });
        assertEquals("keyWrapAlgorithm cannot be null.", exception.getMessage());
    }
}
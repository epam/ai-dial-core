package com.epam.aidial.core.credentials.keymanagement;

import com.epam.aidial.core.credentials.exception.EncryptionException;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.kms.v1.DecryptRequest;
import com.google.cloud.kms.v1.DecryptResponse;
import com.google.cloud.kms.v1.EncryptRequest;
import com.google.cloud.kms.v1.EncryptResponse;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GcpKeyManagementServiceTest {

    private static final String KEY_NAME = "projects/my-project/locations/global/keyRings/my-key-ring/cryptoKeys/my-key";
    private static final byte[] PLAINTEXT = "test-plaintext-for-gcp".getBytes();
    private static final byte[] ENCRYPTED_DATA = "encrypted-data-from-gcp".getBytes();

    @Mock
    private KeyManagementServiceClient mockKmsClient;

    private GcpKeyManagementService gcpKeyManagementService;

    @BeforeEach
    void setUp() {
        gcpKeyManagementService = new GcpKeyManagementService(mockKmsClient, KEY_NAME);
    }

    @Test
    void testEncrypt_success() {
        // Given
        EncryptResponse encryptResponse = EncryptResponse.newBuilder()
                .setCiphertext(ByteString.copyFrom(ENCRYPTED_DATA))
                .build();
        when(mockKmsClient.encrypt(any(EncryptRequest.class))).thenReturn(encryptResponse);

        // When
        byte[] result = gcpKeyManagementService.encrypt(PLAINTEXT);

        // Then
        assertNotNull(result);
        assertArrayEquals(ENCRYPTED_DATA, result);

        ArgumentCaptor<EncryptRequest> requestCaptor = ArgumentCaptor.forClass(EncryptRequest.class);
        verify(mockKmsClient).encrypt(requestCaptor.capture());

        EncryptRequest capturedRequest = requestCaptor.getValue();
        assertEquals(KEY_NAME, capturedRequest.getName());
        assertArrayEquals(PLAINTEXT, capturedRequest.getPlaintext().toByteArray());
    }

    @Test
    void testEncrypt_nullPlaintext() {
        // When & Then
        assertThrows(NullPointerException.class, () -> {
            gcpKeyManagementService.encrypt(null);
        });
    }

    @Test
    void testEncrypt_apiExceptionWithBadRequest() {
        // Given
        ApiException apiException = mock(ApiException.class);
        StatusCode statusCode = mock(StatusCode.class);
        when(statusCode.getCode()).thenReturn(StatusCode.Code.INVALID_ARGUMENT);
        when(apiException.getStatusCode()).thenReturn(statusCode);
        when(mockKmsClient.encrypt(any(EncryptRequest.class))).thenThrow(apiException);

        // When & Then
        EncryptionException encryptionException = assertThrows(EncryptionException.class, () -> {
            gcpKeyManagementService.encrypt(PLAINTEXT);
        });
        assertEquals("Encryption error", encryptionException.getMessage());
        assertSame(apiException, encryptionException.getCause());
    }

    // Test for ApiException with non-BAD_REQUEST status in encrypt
    @Test
    void testEncrypt_apiExceptionWithOtherStatus() {
        // Given
        ApiException apiException = mock(ApiException.class);
        StatusCode statusCode = mock(StatusCode.class);
        when(statusCode.getCode()).thenReturn(StatusCode.Code.INTERNAL);
        when(apiException.getStatusCode()).thenReturn(statusCode);
        when(mockKmsClient.encrypt(any(EncryptRequest.class))).thenThrow(apiException);

        // When & Then
        ApiException thrownException = assertThrows(ApiException.class, () -> {
            gcpKeyManagementService.encrypt(PLAINTEXT);
        });
        assertSame(apiException, thrownException);
    }

    @Test
    void testDecrypt_success() {
        // Given
        DecryptResponse decryptResponse = DecryptResponse.newBuilder()
                .setPlaintext(ByteString.copyFrom(PLAINTEXT))
                .build();
        when(mockKmsClient.decrypt(any(DecryptRequest.class))).thenReturn(decryptResponse);

        // When
        byte[] result = gcpKeyManagementService.decrypt(ENCRYPTED_DATA);

        // Then
        assertNotNull(result);
        assertArrayEquals(PLAINTEXT, result);

        ArgumentCaptor<DecryptRequest> requestCaptor = ArgumentCaptor.forClass(DecryptRequest.class);
        verify(mockKmsClient).decrypt(requestCaptor.capture());

        DecryptRequest capturedRequest = requestCaptor.getValue();
        assertEquals(KEY_NAME, capturedRequest.getName());
        assertArrayEquals(ENCRYPTED_DATA, capturedRequest.getCiphertext().toByteArray());
    }

    @Test
    void testDecrypt_nullEncrypted() {
        // When & Then
        assertThrows(NullPointerException.class, () -> {
            gcpKeyManagementService.decrypt(null);
        });
    }

    @Test
    void testConstructor_nullKmsClient() {
        // When & Then
        NullPointerException exception = assertThrows(NullPointerException.class, () -> {
            new GcpKeyManagementService(null, KEY_NAME);
        });
        assertEquals("kmsClient cannot be null.", exception.getMessage());
    }

    @Test
    void testConstructor_nullKeyName() {
        // When & Then
        NullPointerException exception = assertThrows(NullPointerException.class, () -> {
            new GcpKeyManagementService(mockKmsClient, null);
        });
        assertEquals("keyName cannot be null.", exception.getMessage());
    }

    @Test
    void testDecrypt_apiExceptionWithBadRequest() {
        // Given
        ApiException apiException = mock(ApiException.class);
        StatusCode statusCode = mock(StatusCode.class);
        when(statusCode.getCode()).thenReturn(StatusCode.Code.INVALID_ARGUMENT);
        when(apiException.getStatusCode()).thenReturn(statusCode);
        when(mockKmsClient.decrypt(any(DecryptRequest.class))).thenThrow(apiException);

        // When & Then
        EncryptionException encryptionException = assertThrows(EncryptionException.class, () -> {
            gcpKeyManagementService.decrypt(PLAINTEXT);
        });
        assertEquals("Decryption error", encryptionException.getMessage());
        assertSame(apiException, encryptionException.getCause());
    }

    // Test for ApiException with non-BAD_REQUEST status in encrypt
    @Test
    void testDecrypt_apiExceptionWithOtherStatus() {
        // Given
        ApiException apiException = mock(ApiException.class);
        StatusCode statusCode = mock(StatusCode.class);
        when(statusCode.getCode()).thenReturn(StatusCode.Code.INTERNAL);
        when(apiException.getStatusCode()).thenReturn(statusCode);
        when(mockKmsClient.decrypt(any(DecryptRequest.class))).thenThrow(apiException);

        // When & Then
        ApiException thrownException = assertThrows(ApiException.class, () -> {
            gcpKeyManagementService.decrypt(PLAINTEXT);
        });
        assertSame(apiException, thrownException);
    }
}
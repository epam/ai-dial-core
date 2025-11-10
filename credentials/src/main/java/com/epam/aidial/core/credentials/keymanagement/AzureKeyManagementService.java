package com.epam.aidial.core.credentials.keymanagement;

import com.azure.core.exception.HttpResponseException;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import com.azure.security.keyvault.keys.cryptography.models.UnwrapResult;
import com.azure.security.keyvault.keys.cryptography.models.WrapResult;
import com.epam.aidial.core.credentials.exception.CekEncryptionException;
import com.epam.aidial.core.credentials.exception.EncryptionException;
import com.epam.aidial.core.storage.http.HttpStatus;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

@Slf4j
public class AzureKeyManagementService implements KeyManagementService {

    private final CryptographyClient cryptoClient;
    private final KeyWrapAlgorithm keyWrapAlgorithm;

    public AzureKeyManagementService(CryptographyClient cryptoClient,
                                     KeyWrapAlgorithm keyWrapAlgorithm) {

        this.cryptoClient = Objects.requireNonNull(cryptoClient, "cryptoClient cannot be null.");
        this.keyWrapAlgorithm = Objects.requireNonNull(keyWrapAlgorithm, "keyWrapAlgorithm cannot be null.");
    }

    @Override
    public byte[] encrypt(byte[] plain) {
        try {
            Objects.requireNonNull(plain, "plain cannot be null.");
            WrapResult result = cryptoClient.wrapKey(keyWrapAlgorithm, plain);
            return result.getEncryptedKey();
        } catch (HttpResponseException e) {
            log.debug("Error wrapping content encryption key");
            if (e.getResponse().getStatusCode() == HttpStatus.BAD_REQUEST.getCode()) {
                throw new CekEncryptionException("Encryption error", e);
            }
            throw e;
        }
    }

    @Override
    public byte[] decrypt(byte[] encrypted) {
        try {
            Objects.requireNonNull(encrypted, "encrypted cannot be null.");
            UnwrapResult result = cryptoClient.unwrapKey(keyWrapAlgorithm, encrypted);
            return result.getKey();
        } catch (HttpResponseException e) {
            log.debug("Error unwrapping content encryption key");
            if (e.getResponse().getStatusCode() == HttpStatus.BAD_REQUEST.getCode()) {
                throw new CekEncryptionException("Decryption error", e);
            }
            throw e;
        }
    }

}

package com.epam.aidial.core.credentials.keymanagement;

import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import com.azure.security.keyvault.keys.cryptography.models.UnwrapResult;
import com.azure.security.keyvault.keys.cryptography.models.WrapResult;

import java.util.Objects;

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
        Objects.requireNonNull(plain, "plain cannot be null.");
        WrapResult result = cryptoClient.wrapKey(keyWrapAlgorithm, plain);
        return result.getEncryptedKey();
    }

    @Override
    public byte[] decrypt(byte[] encrypted) {
        Objects.requireNonNull(encrypted, "encrypted cannot be null.");
        UnwrapResult result = cryptoClient.unwrapKey(keyWrapAlgorithm, encrypted);
        return result.getKey();
    }

}

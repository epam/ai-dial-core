package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.data.configuration.EncryptionSettings;
import lombok.SneakyThrows;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public class ContentEncryptionKeyGenerator {

    private final String algorithm;
    private final int keySize;

    public ContentEncryptionKeyGenerator(EncryptionSettings encryptionSettings) {
        this.algorithm = encryptionSettings.getAlgorithm();
        this.keySize = encryptionSettings.getKeySize();
    }

    @SneakyThrows
    public byte[] generate() {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(algorithm);
        keyGenerator.init(keySize);
        SecretKey secretKey = keyGenerator.generateKey();
        return secretKey.getEncoded();
    }

}

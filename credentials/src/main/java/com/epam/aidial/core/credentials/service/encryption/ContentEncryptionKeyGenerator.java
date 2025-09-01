package com.epam.aidial.core.credentials.service.encryption;

import lombok.SneakyThrows;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public class ContentEncryptionKeyGenerator {

    @SneakyThrows
    public byte[] generate() {
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(256);
        SecretKey secretKey = keyGenerator.generateKey();
        return secretKey.getEncoded();
    }

}

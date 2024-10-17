package com.epam.aidial.core.security;

import lombok.experimental.UtilityClass;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

@UtilityClass
public class ApiKeyGenerator {

    private static final String DICT = "0123456789abcdefghijklmnopqrstuvwxyz-_ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final SecureRandom SECURE_RANDOM;

    static {
        try {
            SECURE_RANDOM = SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String generateKey() {
        return SECURE_RANDOM
                .ints(32, 0, DICT.length())
                .mapToObj(DICT::charAt)
                .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append)
                .toString();
    }
}

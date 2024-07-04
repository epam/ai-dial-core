package com.epam.aidial.core.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ETagBuilder {
    private final MessageDigest digest = getDigest();

    public ETagBuilder append(byte[] bytes) {
        digest.update(bytes);
        return this;
    }

    public String build() {
        byte[] bytes = digest.digest();
        return IntStream.range(0, bytes.length)
                .mapToObj(i -> String.format("%02x", bytes[i]))
                .collect(Collectors.joining());
    }

    public MessageDigest getDigest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String generateETag(byte[] bytes) {
        return new ETagBuilder().append(bytes).build();
    }
}

package com.epam.aidial.core.util;

import lombok.SneakyThrows;

import java.security.MessageDigest;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class EtagBuilder {
    private final MessageDigest digest = getDigest();

    public EtagBuilder append(byte[] bytes) {
        digest.update(bytes);
        return this;
    }

    public String build() {
        byte[] bytes = digest.digest();
        return IntStream.range(0, bytes.length)
                .mapToObj(i -> String.format("%02x", bytes[i]))
                .collect(Collectors.joining());
    }

    @SneakyThrows
    public MessageDigest getDigest() {
        return MessageDigest.getInstance("MD5");
    }

    public static String generateEtag(byte[] bytes) {
        return new EtagBuilder().append(bytes).build();
    }
}

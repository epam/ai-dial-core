package com.epam.aidial.core.server.util;

import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.ArrayUtils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

@UtilityClass
public class RedisUtil {
    private static final Charset STRING_ENCODING = StandardCharsets.UTF_8;
    public static final byte[] BOOLEAN_TRUE_ARRAY = "true".getBytes(STRING_ENCODING);
    public static final byte[] BOOLEAN_FALSE_ARRAY = "false".getBytes(STRING_ENCODING);
    public static final byte[] EMPTY_ARRAY = ArrayUtils.EMPTY_BYTE_ARRAY;

    public byte[] booleanToRedis(Boolean value) {
        return javaToRedis(value, v -> v ? BOOLEAN_TRUE_ARRAY : BOOLEAN_FALSE_ARRAY);
    }

    public byte[] longToRedis(Long value) {
        return javaToRedis(value, v -> toBytes(Long.toString(v)));
    }

    public byte[] stringToRedis(String value) {
        return javaToRedis(value, RedisUtil::toBytes);
    }

    public Boolean redisToBoolean(byte[] data) {
        return redisToJava(data, null, d -> Boolean.parseBoolean(toString(d)));
    }

    public Boolean redisToBoolean(byte[] data, boolean defaultValue) {
        Boolean result = redisToJava(data, null, d -> Boolean.parseBoolean(toString(d)));
        return result == null ? defaultValue : result;
    }

    public String redisToString(byte[] data, String defaultValue) {
        return redisToJava(data, defaultValue, RedisUtil::toString);
    }

    public Long redisToLong(byte[] data) {
        return redisToJava(data, null, v -> Long.parseLong(toString(v)));
    }

    private <T> byte[] javaToRedis(T value, Function<T, byte[]> serializer) {
        if (value == null) {
            return EMPTY_ARRAY;
        }

        return serializer.apply(value);
    }

    private <T> T redisToJava(byte[] data, T defaultValue, Function<byte[], T> deserializer) {
        if (ArrayUtils.isEmpty(data)) {
            return defaultValue;
        }

        return deserializer.apply(data);
    }

    private static String toString(byte[] bytes) {
        return new String(bytes, STRING_ENCODING);
    }

    public static byte[] toBytes(String input) {
        return input.getBytes(STRING_ENCODING);
    }
}

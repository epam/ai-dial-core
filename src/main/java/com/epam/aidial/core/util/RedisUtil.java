package com.epam.aidial.core.util;

import com.google.common.primitives.Longs;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.ArrayUtils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

@UtilityClass
public class RedisUtil {
    private static final Charset STRING_ENCODING = StandardCharsets.UTF_8;
    private static final byte[] BOOLEAN_TRUE_ARRAY = new byte[]{1};
    private static final byte[] BOOLEAN_FALSE_ARRAY = new byte[]{0};
    public static final byte[] EMPTY_ARRAY = ArrayUtils.EMPTY_BYTE_ARRAY;

    public byte[] booleanToRedis(Boolean value) {
        return javaToRedis(value, v -> v ? BOOLEAN_TRUE_ARRAY : BOOLEAN_FALSE_ARRAY);
    }

    public byte[] longToRedis(Long value) {
        return javaToRedis(value, Longs::toByteArray);
    }

    public byte[] stringToRedis(String value) {
        return javaToRedis(value, v -> value.getBytes(STRING_ENCODING));
    }

    public Boolean redisToBoolean(byte[] data) {
        return redisToJava(data, null, d -> d[0] == 1);
    }

    public String redisToString(byte[] data, String defaultValue) {
        return redisToJava(data, defaultValue, d -> new String(d, STRING_ENCODING));
    }

    public Long redisToLong(byte[] data) {
        return redisToJava(data, null, Longs::fromByteArray);
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
}
